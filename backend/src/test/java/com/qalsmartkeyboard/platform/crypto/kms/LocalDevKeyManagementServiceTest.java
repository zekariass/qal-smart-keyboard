package com.qalsmartkeyboard.platform.crypto.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.crypto.KeyManagementException;

class LocalDevKeyManagementServiceTest {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<String, String> CONTEXT = Map.of("purpose", "qal.user-data-key", "userId", "u-1",
			"keyVersion", "1");

	private final LocalDevKeyManagementService kms = new LocalDevKeyManagementService("local:test", randomKey(),
			RANDOM);

	static byte[] randomKey() {
		byte[] key = new byte[32];
		RANDOM.nextBytes(key);
		return key;
	}

	@Test
	void wrapsAndUnwrapsUnderTheSameContext() {
		byte[] dek = randomKey();
		WrappedKey wrapped = kms.wrap(dek, CONTEXT);

		assertThat(wrapped.kmsKeyId()).isEqualTo("local:test");
		assertThat(wrapped.ciphertext()).hasSize(1 + 12 + 32 + 16);
		assertThat(wrapped.ciphertext()[0]).isEqualTo(LocalDevKeyManagementService.FORMAT_V1);
		assertThat(Collections.indexOfSubList(asList(wrapped.ciphertext()), asList(dek))).isEqualTo(-1);
		assertThat(kms.unwrap(wrapped, CONTEXT)).isEqualTo(dek);
		assertThat(kms.wrap(dek, CONTEXT).ciphertext()).isNotEqualTo(wrapped.ciphertext()); // fresh nonce
	}

	@Test
	void unwrapFailsForAnotherUserOrVersion() {
		WrappedKey wrapped = kms.wrap(randomKey(), CONTEXT);
		Map<String, String> otherUser = Map.of("purpose", "qal.user-data-key", "userId", "u-2", "keyVersion", "1");
		Map<String, String> otherVersion = Map.of("purpose", "qal.user-data-key", "userId", "u-1", "keyVersion", "2");

		assertThatThrownBy(() -> kms.unwrap(wrapped, otherUser)).isInstanceOf(KeyManagementException.class);
		assertThatThrownBy(() -> kms.unwrap(wrapped, otherVersion)).isInstanceOf(KeyManagementException.class);
		assertThatThrownBy(() -> kms.unwrap(wrapped, Map.of())).isInstanceOf(KeyManagementException.class);
	}

	@Test
	void everyAlteredByteIsRejected() {
		WrappedKey wrapped = kms.wrap(randomKey(), CONTEXT);
		for (int i = 0; i < wrapped.ciphertext().length; i++) {
			byte[] altered = wrapped.ciphertext().clone();
			altered[i] ^= 0x01;
			WrappedKey tampered = new WrappedKey(wrapped.kmsKeyId(), altered);
			assertThatThrownBy(() -> kms.unwrap(tampered, CONTEXT)).isInstanceOf(KeyManagementException.class);
		}
		byte[] truncated = java.util.Arrays.copyOf(wrapped.ciphertext(), 20);
		assertThatThrownBy(() -> kms.unwrap(new WrappedKey("local:test", truncated), CONTEXT))
				.isInstanceOf(KeyManagementException.class);
	}

	@Test
	void anotherKekOrKeyIdCannotUnwrap() {
		WrappedKey wrapped = kms.wrap(randomKey(), CONTEXT);
		LocalDevKeyManagementService otherKek = new LocalDevKeyManagementService("local:test", randomKey(), RANDOM);

		assertThatThrownBy(() -> otherKek.unwrap(wrapped, CONTEXT)).isInstanceOf(KeyManagementException.class);
		assertThatThrownBy(() -> kms.unwrap(new WrappedKey("local:other", wrapped.ciphertext()), CONTEXT))
				.isInstanceOf(KeyManagementException.class)
				.hasMessageContaining("does not hold");
	}

	@Test
	void constructorValidatesKeyIdAndKek() {
		assertThatThrownBy(() -> new LocalDevKeyManagementService("dev-1", randomKey(), RANDOM))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LocalDevKeyManagementService("local:x", new byte[16], RANDOM))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void contextEncodingIsOrderIndependentAndUnambiguous() {
		assertThat(KmsContext.canonicalBytes(Map.of("a", "1", "b", "2")))
				.isEqualTo(KmsContext.canonicalBytes(new java.util.TreeMap<>(Map.of("b", "2", "a", "1"))));
		assertThat(KmsContext.canonicalBytes(Map.of("a", "bc")))
				.isNotEqualTo(KmsContext.canonicalBytes(Map.of("ab", "c")));
	}

	private static List<Byte> asList(byte[] bytes) {
		Byte[] boxed = new Byte[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			boxed[i] = bytes[i];
		}
		return List.of(boxed);
	}
}
