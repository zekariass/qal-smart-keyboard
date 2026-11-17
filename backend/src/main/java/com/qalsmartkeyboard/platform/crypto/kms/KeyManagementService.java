package com.qalsmartkeyboard.platform.crypto.kms;

import java.util.Map;

import com.qalsmartkeyboard.platform.crypto.KeyManagementException;

/**
 * Port to the key management service that holds the key-encryption key (KEK). Plaintext data keys are only
 * ever wrapped/unwrapped here; the KEK itself never leaves the KMS (the local dev adapter keeps it in memory).
 *
 * <p>{@code context} is authenticated, not encrypted: unwrap succeeds only with the exact context used to
 * wrap. It maps directly to an AWS KMS {@code EncryptionContext} and, canonically encoded by
 * {@link KmsContext#canonicalBytes}, to GCP Cloud KMS {@code additionalAuthenticatedData}.
 *
 * <p>Adapters: {@link LocalDevKeyManagementService} (development and tests only) and
 * {@link CloudKeyManagementService} (delegates to a {@link CloudKmsClient}). See ADR 0005.
 */
public interface KeyManagementService {

	/** Identifier of the KEK new wraps use; stored in {@code user_data_keys.kms_key_id}. */
	String activeKeyId();

	/**
	 * Wraps {@code plaintextKey} under the active KEK. Does not modify or keep {@code plaintextKey}.
	 *
	 * @throws KeyManagementException if the KMS fails
	 */
	WrappedKey wrap(byte[] plaintextKey, Map<String, String> context);

	/**
	 * Unwraps a key wrapped by {@link #wrap}. The caller owns (and should wipe) the returned array.
	 *
	 * @throws KeyManagementException if the KEK is unknown, the KMS fails, or authentication fails (wrong
	 *                                context or altered bytes)
	 */
	byte[] unwrap(WrappedKey wrappedKey, Map<String, String> context);
}
