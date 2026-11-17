package com.qalsmartkeyboard.platform.crypto.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.crypto.KeyManagementException;

class CloudKeyManagementServiceTest {

	private static final String KEY = "projects/p/locations/l/keyRings/r/cryptoKeys/user-dek";

	/** Records calls; "encrypts" by prefixing the key name so decrypt can check it. */
	static final class RecordingClient implements CloudKmsClient {
		final List<String> calls = new ArrayList<>();
		boolean fail;

		@Override
		public byte[] encrypt(String keyName, byte[] plaintext, Map<String, String> context) {
			calls.add("encrypt " + keyName + " " + context.get("userId"));
			if (fail) {
				throw new IllegalStateException("provider said: secret-token-123");
			}
			return plaintext.clone();
		}

		@Override
		public byte[] decrypt(String keyName, byte[] ciphertext, Map<String, String> context) {
			calls.add("decrypt " + keyName + " " + context.get("userId"));
			if (fail) {
				throw new IllegalStateException("provider said: secret-token-123");
			}
			return ciphertext.clone();
		}
	}

	@Test
	void wrapsWithTheConfiguredKeyAndUnwrapsWithTheStoredOne() {
		RecordingClient client = new RecordingClient();
		CloudKeyManagementService kms = new CloudKeyManagementService(client, KEY);

		WrappedKey wrapped = kms.wrap(new byte[] { 1, 2 }, Map.of("userId", "u"));
		assertThat(wrapped.kmsKeyId()).isEqualTo(KEY);
		assertThat(kms.activeKeyId()).isEqualTo(KEY);

		kms.unwrap(new WrappedKey("arn:aws:kms:old-key", wrapped.ciphertext()), Map.of("userId", "u"));
		assertThat(client.calls).containsExactly("encrypt " + KEY + " u", "decrypt arn:aws:kms:old-key u");
	}

	@Test
	void providerErrorsAreWrappedWithoutTheirMessage() {
		RecordingClient client = new RecordingClient();
		client.fail = true;
		CloudKeyManagementService kms = new CloudKeyManagementService(client, KEY);

		assertThatThrownBy(() -> kms.wrap(new byte[32], Map.of("userId", "u")))
				.isInstanceOf(KeyManagementException.class)
				.hasNoCause()
				.message().doesNotContain("secret-token-123");
		assertThatThrownBy(() -> kms.unwrap(new WrappedKey(KEY, new byte[8]), Map.of("userId", "u")))
				.isInstanceOf(KeyManagementException.class)
				.hasNoCause()
				.message().doesNotContain("secret-token-123");
	}

	@Test
	void requiresAKeyName() {
		assertThatThrownBy(() -> new CloudKeyManagementService(new RecordingClient(), " "))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
