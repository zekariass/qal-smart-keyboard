package com.qalsmartkeyboard.platform.crypto.internal;

import java.security.SecureRandom;
import java.time.InstantSource;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.crypto.kms.Aes256Gcm;
import com.qalsmartkeyboard.platform.crypto.kms.CloudKeyManagementService;
import com.qalsmartkeyboard.platform.crypto.kms.CloudKmsClient;
import com.qalsmartkeyboard.platform.crypto.kms.KeyManagementService;
import com.qalsmartkeyboard.platform.crypto.kms.LocalDevKeyManagementService;

/**
 * Wires the KMS adapter and {@link UserDataEncryption}. Fails closed at startup: no provider, a local KMS in a
 * production-like profile, or a local KMS without a KEK (unless the dev-only ephemeral switch is on) all stop
 * the application.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoProperties.class)
class CryptoConfiguration {

	/** Profiles in which the local development KMS is refused, whatever else is configured. */
	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";

	private static final Logger log = LoggerFactory.getLogger(CryptoConfiguration.class);

	/** Not a bean, so no other module picks it up by type. Thread-safe and non-blocking. */
	private final SecureRandom random = new SecureRandom();

	@Bean
	KeyManagementService keyManagementService(CryptoProperties properties, Environment environment,
			ObjectProvider<CloudKmsClient> cloudClient) {
		String provider = properties.kms().provider();
		if (provider == null || provider.isBlank()) {
			throw new IllegalStateException(
					"qal.crypto.kms.provider is not set (env QAL_KMS_PROVIDER): use 'cloud', or 'local' for development");
		}
		return switch (provider.trim().toLowerCase(Locale.ROOT)) {
			case "local" -> localKms(properties.localKms(), environment, random);
			case "cloud" -> cloudKms(properties.cloudKms(), cloudClient);
			default -> throw new IllegalStateException("Unknown qal.crypto.kms.provider; use 'cloud' or 'local'");
		};
	}

	@Bean
	UserDataEncryption userDataEncryption(JdbcClient jdbcClient, KeyManagementService keyManagementService,
			PlatformTransactionManager transactionManager, CryptoProperties properties) {
		DataKeyCache cache = new DataKeyCache(properties.dekCache().maxEntries(), properties.dekCache().ttl(),
				InstantSource.system());
		return new EnvelopeEncryptionService(new JdbcUserDataKeyStore(jdbcClient), keyManagementService, cache,
				transactionManager, random);
	}

	static LocalDevKeyManagementService localKms(CryptoProperties.LocalKms settings, Environment environment,
			SecureRandom random) {
		if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
			throw new IllegalStateException(
					"The local development KMS is refused under the prod/production/staging profiles; use qal.crypto.kms.provider=cloud");
		}
		String encoded = settings.kek();
		if (encoded != null && !encoded.isBlank()) {
			byte[] kek;
			try {
				kek = Base64.getDecoder().decode(encoded.trim());
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("qal.crypto.local-kms.kek is not valid base64");
			}
			try {
				if (kek.length != Aes256Gcm.KEY_BYTES) {
					throw new IllegalStateException("qal.crypto.local-kms.kek must decode to exactly 32 bytes");
				}
				return new LocalDevKeyManagementService(LocalDevKeyManagementService.KEY_ID_PREFIX + settings.kekId(),
						kek, random);
			} finally {
				Arrays.fill(kek, (byte) 0);
			}
		}
		if (settings.allowEphemeralKek()) {
			byte[] kek = new byte[Aes256Gcm.KEY_BYTES];
			random.nextBytes(kek);
			byte[] suffix = new byte[4];
			random.nextBytes(suffix);
			String keyId = LocalDevKeyManagementService.KEY_ID_PREFIX + "ephemeral-" + HexFormat.of().formatHex(suffix);
			log.warn("Local dev KMS uses an EPHEMERAL key-encryption key ({}); encrypted user data will be unreadable "
					+ "after a restart. Never use this outside development and tests.", keyId);
			try {
				return new LocalDevKeyManagementService(keyId, kek, random);
			} finally {
				Arrays.fill(kek, (byte) 0);
			}
		}
		throw new IllegalStateException("qal.crypto.kms.provider=local needs qal.crypto.local-kms.kek "
				+ "(env QAL_LOCAL_KMS_KEK, base64 of 32 random bytes); "
				+ "qal.crypto.local-kms.allow-ephemeral-kek=true is for development and tests only");
	}

	private static CloudKeyManagementService cloudKms(CryptoProperties.CloudKms settings,
			ObjectProvider<CloudKmsClient> cloudClient) {
		CloudKmsClient client = cloudClient.getIfAvailable();
		if (client == null) {
			throw new IllegalStateException("qal.crypto.kms.provider=cloud needs a CloudKmsClient bean; "
					+ "no cloud KMS client is implemented yet (see ADR 0005)");
		}
		if (settings.keyName() == null || settings.keyName().isBlank()) {
			throw new IllegalStateException("qal.crypto.kms.provider=cloud needs qal.crypto.cloud-kms.key-name");
		}
		return new CloudKeyManagementService(client, settings.keyName().trim());
	}
}
