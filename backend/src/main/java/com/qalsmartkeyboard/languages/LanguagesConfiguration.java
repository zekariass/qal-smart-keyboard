package com.qalsmartkeyboard.languages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;

import com.qalsmartkeyboard.administration.AdminAccess;
import com.qalsmartkeyboard.administration.AuditTrail;
import com.qalsmartkeyboard.languages.pack.KmsPackSigner;
import com.qalsmartkeyboard.languages.pack.KmsSigningClient;
import com.qalsmartkeyboard.languages.pack.LicenseAllowList;
import com.qalsmartkeyboard.languages.pack.LocalEd25519PackSigner;
import com.qalsmartkeyboard.languages.pack.PackSigner;
import com.qalsmartkeyboard.languages.pack.UnconfiguredPackSigner;
import com.qalsmartkeyboard.platform.outbox.OutboxPublisher;

import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the pack-URL signer and admin pack publishing (B3.2). Fails closed: under the prod/production/staging profiles
 * the application does not start without object-store credentials (a signer bean from elsewhere, such as a test fake,
 * replaces this one) and never signs packs with an ephemeral key.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ LanguagesProperties.class, PackPublishingProperties.class })
class LanguagesConfiguration {

	static final String PRODUCTION_LIKE_PROFILES = "prod | production | staging";
	static final String LICENSES_CLASSPATH = "languages/licenses.json";
	private static final Logger log = LoggerFactory.getLogger(LanguagesConfiguration.class);

	@Bean
	ArtifactUrlSigner artifactUrlSigner(LanguagesProperties properties, Environment environment,
			ObjectProvider<Clock> clock) {
		LanguagesProperties.Artifacts artifacts = properties.artifacts();
		if (!artifacts.configured()) {
			if (environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES))) {
				throw new IllegalStateException("qal.languages.artifacts.endpoint, access-key and secret-key are required under"
						+ " the prod/production/staging profiles (env QAL_PACKS_S3_ENDPOINT, QAL_PACKS_S3_ACCESS_KEY,"
						+ " QAL_PACKS_S3_SECRET_KEY)");
			}
			return new UnconfiguredArtifactUrlSigner();
		}
		return new S3PresignedArtifactUrlSigner(artifacts, clock.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	LanguagesService languagesService(LanguagesRepository repository, ArtifactUrlSigner signer,
			LanguagesProperties properties) {
		return new LanguagesService(repository, signer, properties.internalChannelEnabled());
	}

	/**
	 * The server's pack signing key ({@code qal.languages.publishing.signing}): {@code local} with a configured 32-byte
	 * seed, or an ephemeral one (development and tests only, refused under prod/production/staging); {@code kms} with a
	 * {@link KmsSigningClient} adapter (none ships yet, ADR 0019). Without either, staging and publishing fail at use.
	 */
	@Bean
	PackSigner packSigner(PackPublishingProperties properties, Environment environment,
			ObjectProvider<KmsSigningClient> kms) {
		PackPublishingProperties.Signing signing = properties.signing();
		boolean productionLike = environment.acceptsProfiles(Profiles.of(PRODUCTION_LIKE_PROFILES));
		String provider = signing.provider() == null ? "local" : signing.provider().strip().toLowerCase(Locale.ROOT);
		if ("kms".equals(provider)) {
			KmsSigningClient client = kms.getIfAvailable();
			if (client == null) {
				throw new IllegalStateException("qal.languages.publishing.signing.provider=kms needs a KmsSigningClient "
						+ "adapter, and none is configured in this build");
			}
			return new KmsPackSigner(client, signing.kmsKeyName(), signing.keyId());
		}
		if (!"local".equals(provider)) {
			throw new IllegalStateException("qal.languages.publishing.signing.provider must be local or kms");
		}
		if (signing.privateKey() != null && !signing.privateKey().isBlank()) {
			if (signing.keyId() == null || signing.keyId().isBlank()) {
				throw new IllegalStateException("qal.languages.publishing.signing.key-id is required with a private key "
						+ "(env QAL_PACK_SIGNING_KEY_ID)");
			}
			return new LocalEd25519PackSigner(signing.keyId().strip(), seed(signing.privateKey()));
		}
		if (signing.allowEphemeral()) {
			if (productionLike) {
				throw new IllegalStateException("qal.languages.publishing.signing.allow-ephemeral is refused under the "
						+ "prod/production/staging profiles; configure the pack signing key");
			}
			SecureRandom random = new SecureRandom();
			byte[] seed = new byte[32];
			random.nextBytes(seed);
			byte[] suffix = new byte[4];
			random.nextBytes(suffix);
			String keyId = "ephemeral-" + HexFormat.of().formatHex(suffix);
			log.warn("Language packs are signed with an EPHEMERAL Ed25519 key ({}): no client pins it. Development and "
					+ "tests only.", keyId);
			return new LocalEd25519PackSigner(keyId, seed);
		}
		if (productionLike) {
			log.warn("No pack signing key is configured (QAL_PACK_SIGNING_KEY_ID, QAL_PACK_SIGNING_PRIVATE_KEY): "
					+ "staging and publishing language packs will fail.");
		}
		return new UnconfiguredPackSigner();
	}

	/** Base64 or hex of exactly 32 bytes. */
	static byte[] seed(String value) {
		String v = value.strip();
		byte[] seed;
		try {
			seed = v.length() == 64 ? HexFormat.of().parseHex(v) : Base64.getDecoder().decode(v);
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("qal.languages.publishing.signing.private-key must be base64 or hex");
		}
		if (seed.length != 32) {
			throw new IllegalStateException("qal.languages.publishing.signing.private-key must be a 32-byte Ed25519 seed");
		}
		return seed;
	}

	/** Write access to the pack bucket; without the write credential uploads fail at use. */
	@Bean
	PackObjectStore packObjectStore(LanguagesProperties languages, PackPublishingProperties properties,
			ObjectProvider<Clock> clock) {
		PackPublishingProperties.Storage storage = properties.storage();
		LanguagesProperties.Artifacts artifacts = languages.artifacts();
		String endpoint = storage.endpoint() != null && !storage.endpoint().isBlank() ? storage.endpoint()
				: artifacts.endpoint();
		if (!storage.configured() || endpoint == null || endpoint.isBlank()) {
			return new UnconfiguredPackObjectStore();
		}
		return new S3PackObjectStore(endpoint, artifacts.bucket(), artifacts.region(), artifacts.pathStyle(),
				storage.accessKey(), storage.secretKey(), clock.getIfAvailable(Clock::systemUTC));
	}

	/**
	 * The licence allow-list: the configured file (normally {@code language-data/licenses.json}) or the classpath copy,
	 * which a test keeps identical to {@code language-data/licenses.json}. Unreadable: no start.
	 */
	@Bean
	LicenseAllowList licenseAllowList(PackPublishingProperties properties) {
		String path = properties.licenseAllowList();
		try (InputStream in = path != null && !path.isBlank() ? Files.newInputStream(Path.of(path.strip()))
				: new ClassPathResource(LICENSES_CLASSPATH).getInputStream()) {
			return LicenseAllowList.load(in);
		}
		catch (IOException e) {
			throw new IllegalStateException("The licence allow-list could not be read: " + e.getMessage());
		}
	}

	@Bean
	PackPublishingService packPublishingService(PublishingRepository repository, PackObjectStore store,
			PackSigner signer, LicenseAllowList licenses, AdminAccess access, AuditTrail audit, OutboxPublisher outbox,
			PlatformTransactionManager transactions, JsonMapper json, PackPublishingProperties properties) {
		return new PackPublishingService(repository, store, signer, licenses, access, audit, outbox, transactions, json,
				properties.maxContainerBytes());
	}

	@Bean
	SigningKeyAdminService signingKeyAdminService(PublishingRepository repository, PackSigner signer,
			AdminAccess access, AuditTrail audit, PlatformTransactionManager transactions) {
		return new SigningKeyAdminService(repository, signer, access, audit, transactions);
	}
}
