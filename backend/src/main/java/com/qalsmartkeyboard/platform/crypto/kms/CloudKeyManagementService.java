package com.qalsmartkeyboard.platform.crypto.kms;

import java.util.Map;
import java.util.Objects;

import com.qalsmartkeyboard.platform.crypto.KeyManagementException;

/**
 * Cloud KMS adapter: data keys are wrapped by a key that never leaves the managed KMS (or HSM). The stored
 * {@code kms_key_id} is the configured key name (GCP CryptoKey resource name, AWS key ARN); unwrap uses the
 * name stored with the row, so changing the configured key later only affects new wraps.
 */
public final class CloudKeyManagementService implements KeyManagementService {

	private final CloudKmsClient client;
	private final String keyName;

	public CloudKeyManagementService(CloudKmsClient client, String keyName) {
		this.client = Objects.requireNonNull(client, "client");
		this.keyName = Objects.requireNonNull(keyName, "keyName");
		if (keyName.isBlank() || keyName.length() > 255) {
			throw new IllegalArgumentException("cloud KMS key name must be 1-255 characters");
		}
	}

	@Override
	public String activeKeyId() {
		return keyName;
	}

	@Override
	public WrappedKey wrap(byte[] plaintextKey, Map<String, String> context) {
		try {
			return new WrappedKey(keyName, client.encrypt(keyName, plaintextKey, context));
		} catch (RuntimeException e) {
			// Only the exception type is kept: provider messages could echo request fields.
			throw new KeyManagementException("Cloud KMS encrypt failed (" + e.getClass().getSimpleName() + ")");
		}
	}

	@Override
	public byte[] unwrap(WrappedKey wrappedKey, Map<String, String> context) {
		try {
			return client.decrypt(wrappedKey.kmsKeyId(), wrappedKey.ciphertext(), context);
		} catch (RuntimeException e) {
			throw new KeyManagementException("Cloud KMS decrypt failed (" + e.getClass().getSimpleName() + ")");
		}
	}
}
