package com.qalsmartkeyboard.platform.crypto.kms;

import java.util.Map;

/**
 * Extension point for a managed cloud KMS. No implementation ships yet (the cloud and region are open,
 * ADR 0001 OI-04), so there is no cloud SDK dependency. Selecting {@code qal.crypto.kms.provider=cloud}
 * without a bean of this type fails at startup.
 *
 * <p>How a provider slots in (ADR 0005):
 * <ul>
 * <li><b>GCP Cloud KMS:</b> {@code KeyManagementServiceClient.encrypt(EncryptRequest)} with {@code name =
 * keyName} (a {@code projects/../cryptoKeys/..} resource), {@code plaintext} and
 * {@code additionalAuthenticatedData = KmsContext.canonicalBytes(context)}; {@code decrypt} with the same
 * name and AAD. Cloud KMS picks the key version from the ciphertext, so KEK rotation needs no change
 * here.</li>
 * <li><b>AWS KMS:</b> {@code KmsClient.encrypt(keyId = keyName, plaintext, encryptionContext = context)} and
 * {@code decrypt(ciphertextBlob, encryptionContext = context, keyId = keyName)}.</li>
 * </ul>
 * Implementations throw any exception on failure; {@link CloudKeyManagementService} wraps it without
 * exposing data.
 */
public interface CloudKmsClient {

	/** Encrypts {@code plaintext} under {@code keyName}, authenticating {@code context}. */
	byte[] encrypt(String keyName, byte[] plaintext, Map<String, String> context);

	/** Decrypts a blob produced by {@link #encrypt} with the same key name and context. */
	byte[] decrypt(String keyName, byte[] ciphertext, Map<String, String> context);
}
