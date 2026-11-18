package com.qalsmartkeyboard.platform.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.InstantSource;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException.Reason;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.KeyManagementException;
import com.qalsmartkeyboard.platform.crypto.internal.UserDataKeyStore.KeyRecord;
import com.qalsmartkeyboard.platform.crypto.internal.UserDataKeyStore.KeyStatus;
import com.qalsmartkeyboard.platform.crypto.kms.KeyManagementService;
import com.qalsmartkeyboard.platform.crypto.kms.LocalDevKeyManagementService;
import com.qalsmartkeyboard.platform.crypto.kms.WrappedKey;

class EnvelopeEncryptionServiceTest {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final String SECRET = "ሰላም secret text";
	private static final EncryptionContext LABEL = EncryptionContext.of("user_destination_profiles",
			"label_ciphertext", UUID.fromString("11111111-1111-1111-1111-111111111111"));

	private final InMemoryUserDataKeyStore store = new InMemoryUserDataKeyStore();
	private final CountingKms kms = new CountingKms(new LocalDevKeyManagementService("local:unit", key(), RANDOM));
	private final DataKeyCache cache = new DataKeyCache(100, Duration.ofMinutes(5), InstantSource.system());
	private final EnvelopeEncryptionService service = new EnvelopeEncryptionService(store, kms, cache,
			new NoOpTransactionManager(), RANDOM);

	private static byte[] key() {
		byte[] key = new byte[32];
		RANDOM.nextBytes(key);
		return key;
	}

	@Test
	void roundTripCreatesTheKeyLazilyOnce() {
		UUID user = store.newUser();
		assertThat(store.findAll(user)).isEmpty();

		EncryptedValue first = service.encryptString(user, LABEL, SECRET);
		EncryptedValue second = service.encryptString(user, LABEL, SECRET);

		assertThat(service.decryptString(user, LABEL, first.ciphertext())).isEqualTo(SECRET);
		assertThat(service.decryptString(user, LABEL, second.ciphertext())).isEqualTo(SECRET);
		assertThat(store.findAll(user)).singleElement()
				.satisfies(k -> assertThat(k.status()).isEqualTo(KeyStatus.ACTIVE))
				.satisfies(k -> assertThat(k.keyVersion()).isEqualTo(1));
		assertThat(first.keyVersion()).isEqualTo(1);
		assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext()); // random nonce per value
		assertThat(kms.wraps.get()).isEqualTo(1);
	}

	/** B2.6: moving a value to another user re-encrypts it; the old bytes never open as the new owner. */
	@Test
	void reencryptMovesAValueToAnotherUserAndRowKeepingNulls() {
		UUID guest = store.newUser();
		UUID target = store.newUser();
		service.encryptString(target, LABEL, "x");
		service.rotateKey(target); // the target is on another key version (2) than the guest (1)
		EncryptionContext signoff = EncryptionContext.of("user_style_profiles", "signoff_ciphertext", "row-1");
		EncryptedValue label = service.encryptString(guest, LABEL, SECRET);
		EncryptedValue sign = service.encryptString(guest, signoff, "bye");

		EncryptedValue moved = service.reencrypt(guest, LABEL, label.ciphertext(), target, LABEL);
		assertThat(moved.keyVersion()).isEqualTo(2);
		assertThat(service.decryptString(target, LABEL, moved.ciphertext())).isEqualTo(SECRET);
		assertRejected(() -> service.decrypt(target, LABEL, label.ciphertext()), Reason.AUTHENTICATION_FAILED);

		java.util.List<EncryptedValue> row = service.reencryptRow(guest, target,
				java.util.List.of(LABEL, signoff, LABEL),
				java.util.Arrays.asList(label.ciphertext(), null, label.ciphertext()));
		assertThat(row).hasSize(3);
		assertThat(row.get(1)).isNull();
		assertThat(row.get(0).keyVersion()).isEqualTo(row.get(2).keyVersion()).isEqualTo(2);
		assertThat(service.decryptString(target, LABEL, row.get(0).ciphertext())).isEqualTo(SECRET);
		// a value sealed for another row of the guest does not open under this row's context
		assertThatThrownBy(() -> service.reencrypt(guest, LABEL, sign.ciphertext(), target, LABEL))
				.isInstanceOf(CiphertextRejectedException.class);

		service.cryptoShred(guest);
		assertThatThrownBy(() -> service.reencrypt(guest, LABEL, label.ciphertext(), target, LABEL))
				.isInstanceOf(CryptoShreddedException.class);
	}

	@Test
	void ciphertextIsSelfDescribing() {
		UUID user = store.newUser();
		byte[] plaintext = new byte[] { 1, 2, 3, 4, 5 };
		byte[] ct = service.encrypt(user, LABEL, plaintext).ciphertext();

		assertThat(ct).hasSize(1 + 4 + 12 + plaintext.length + 16);
		assertThat(ct[0]).isEqualTo((byte) 1);
		assertThat(ByteBuffer.wrap(ct, 1, 4).getInt()).isEqualTo(1);
		assertThat(service.decrypt(user, LABEL, ct)).isEqualTo(plaintext);
		assertThat(service.decrypt(user, LABEL, service.encrypt(user, LABEL, new byte[0]).ciphertext())).isEmpty();
	}

	@Test
	void everyAlteredByteFailsAuthentication() {
		UUID user = store.newUser();
		byte[] ct = service.encryptString(user, LABEL, SECRET).ciphertext();
		for (int i = CiphertextFormat.NONCE_OFFSET; i < ct.length; i++) { // nonce, body and tag
			byte[] altered = ct.clone();
			altered[i] ^= 0x01;
			assertRejected(() -> service.decrypt(user, LABEL, altered), Reason.AUTHENTICATION_FAILED);
		}
		byte[] truncated = java.util.Arrays.copyOf(ct, ct.length - 1);
		assertRejected(() -> service.decrypt(user, LABEL, truncated), Reason.AUTHENTICATION_FAILED);
		byte[] format = ct.clone();
		format[0] = 2;
		assertRejected(() -> service.decrypt(user, LABEL, format), Reason.UNSUPPORTED_FORMAT);
	}

	@Test
	void keyVersionIsBoundToTheCiphertext() {
		UUID user = store.newUser();
		byte[] v1 = service.encryptString(user, LABEL, SECRET).ciphertext();
		service.rotateKey(user);

		byte[] claimsV2 = v1.clone();
		ByteBuffer.wrap(claimsV2).putInt(1, 2);
		assertRejected(() -> service.decrypt(user, LABEL, claimsV2), Reason.AUTHENTICATION_FAILED);
		byte[] claimsV9 = v1.clone();
		ByteBuffer.wrap(claimsV9).putInt(1, 9);
		assertRejected(() -> service.decrypt(user, LABEL, claimsV9), Reason.UNKNOWN_KEY_VERSION);
	}

	@Test
	void ciphertextCannotMoveToAnotherRowColumnTableOrUser() {
		UUID user = store.newUser();
		UUID other = store.newUser();
		service.encryptString(other, LABEL, "other has a v1 key");
		byte[] ct = service.encryptString(user, LABEL, SECRET).ciphertext();

		assertRejected(() -> service.decrypt(user, EncryptionContext.of("user_destination_profiles",
				"label_ciphertext", UUID.randomUUID()), ct), Reason.AUTHENTICATION_FAILED);
		assertRejected(() -> service.decrypt(user, EncryptionContext.of("user_destination_profiles",
				"other_ciphertext", LABEL.rowId()), ct), Reason.AUTHENTICATION_FAILED);
		assertRejected(() -> service.decrypt(user, EncryptionContext.of("user_history_items",
				"label_ciphertext", LABEL.rowId()), ct), Reason.AUTHENTICATION_FAILED);
		assertRejected(() -> service.decrypt(other, LABEL, ct), Reason.AUTHENTICATION_FAILED);
		assertRejected(() -> service.decrypt(store.newUser(), LABEL, ct), Reason.UNKNOWN_KEY_VERSION);
	}

	@Test
	void wrappedDekCopiedToAnotherUserIsRejectedByTheKms() {
		UUID alice = store.newUser();
		UUID bob = store.newUser();
		service.encryptString(alice, LABEL, "alice");
		byte[] bobCt = service.encryptString(bob, LABEL, "bob").ciphertext();
		KeyRecord aliceKey = store.find(alice, 1).orElseThrow();

		store.replace(bob, new KeyRecord(1, KeyStatus.ACTIVE, aliceKey.wrapped()));
		service.clearCache();

		assertThatThrownBy(() -> service.decrypt(bob, LABEL, bobCt)).isInstanceOf(KeyManagementException.class);
		assertThatThrownBy(() -> service.encryptString(bob, LABEL, "x")).isInstanceOf(KeyManagementException.class);
	}

	@Test
	void rotationKeepsOldVersionsReadable() {
		UUID user = store.newUser();
		byte[] v1 = service.encryptString(user, LABEL, "old").ciphertext();

		assertThat(service.rotateKey(user)).isEqualTo(2);
		EncryptedValue v2 = service.encryptString(user, LABEL, "new");

		assertThat(v2.keyVersion()).isEqualTo(2);
		assertThat(service.decryptString(user, LABEL, v1)).isEqualTo("old");
		assertThat(service.decryptString(user, LABEL, v2.ciphertext())).isEqualTo("new");
		assertThat(store.findAll(user)).extracting(KeyRecord::status).containsExactly(KeyStatus.ROTATED,
				KeyStatus.ACTIVE);
		assertThat(service.rotateKey(store.newUser())).isEqualTo(1); // no key yet: rotate creates version 1
	}

	@Test
	void shredDestroysEveryVersionAndIsFinal() {
		UUID user = store.newUser();
		byte[] v1 = service.encryptString(user, LABEL, SECRET).ciphertext();
		service.rotateKey(user);
		byte[] v2 = service.encryptString(user, LABEL, SECRET).ciphertext();

		assertThat(service.cryptoShred(user)).isEqualTo(2);

		assertThat(store.findAll(user)).allSatisfy(k -> {
			assertThat(k.status()).isEqualTo(KeyStatus.DESTROYED);
			assertThat(k.wrapped()).isNull();
		});
		assertThatThrownBy(() -> service.decrypt(user, LABEL, v1)).isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> service.decrypt(user, LABEL, v2)).isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> service.encryptString(user, LABEL, "new"))
				.isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> service.rotateKey(user)).isInstanceOf(CryptoShreddedException.class);
		assertThat(service.isShredded(user)).isTrue();
		assertThat(cache.size()).isZero();
		assertThat(service.cryptoShred(user)).isZero(); // idempotent
	}

	@Test
	void shreddingAUserWithoutKeysPreventsLaterKeys() {
		UUID user = store.newUser();
		assertThat(service.cryptoShred(user)).isZero();

		assertThat(store.findAll(user)).singleElement()
				.satisfies(k -> assertThat(k.status()).isEqualTo(KeyStatus.DESTROYED));
		assertThatThrownBy(() -> service.encryptString(user, LABEL, "x")).isInstanceOf(CryptoShreddedException.class);
		assertThat(service.cryptoShred(user)).isZero();
	}

	@Test
	void unknownUsers() {
		UUID ghost = UUID.randomUUID();
		assertThatThrownBy(() -> service.encryptString(ghost, LABEL, "x")).isInstanceOf(IllegalArgumentException.class);
		assertThat(service.cryptoShred(ghost)).isZero();
		assertThat(service.isShredded(ghost)).isFalse();
	}

	@Test
	void cacheAvoidsRepeatedUnwraps() {
		UUID user = store.newUser();
		byte[] ct = service.encryptString(user, LABEL, SECRET).ciphertext();
		service.decrypt(user, LABEL, ct);
		service.decrypt(user, LABEL, ct);
		assertThat(kms.unwraps.get()).isZero(); // the freshly created DEK was cached

		service.clearCache();
		service.decrypt(user, LABEL, ct);
		service.decrypt(user, LABEL, ct);
		assertThat(kms.unwraps.get()).isEqualTo(1);
	}

	@Test
	void exceptionMessagesNeverContainContent() {
		UUID user = store.newUser();
		byte[] ct = service.encryptString(user, LABEL, SECRET).ciphertext();
		byte[] altered = ct.clone();
		altered[ct.length - 1] ^= 1;
		Throwable rejected = org.assertj.core.api.Assertions.catchThrowable(() -> service.decrypt(user, LABEL, altered));
		service.cryptoShred(user);
		Throwable shredded = org.assertj.core.api.Assertions.catchThrowable(() -> service.decrypt(user, LABEL, ct));

		for (Throwable t : new Throwable[] { rejected, shredded }) {
			assertThat(t.getMessage()).doesNotContain(SECRET).doesNotContain(user.toString())
					.doesNotContain(java.util.HexFormat.of().formatHex(ct).substring(0, 20));
		}
	}

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
		assertThatThrownBy(call).isInstanceOfSatisfying(CiphertextRejectedException.class,
				e -> assertThat(e.reason()).isEqualTo(reason));
	}

	/** Counts KMS calls. */
	static final class CountingKms implements KeyManagementService {
		final KeyManagementService delegate;
		final AtomicInteger wraps = new AtomicInteger();
		final AtomicInteger unwraps = new AtomicInteger();

		CountingKms(KeyManagementService delegate) {
			this.delegate = delegate;
		}

		@Override
		public String activeKeyId() {
			return delegate.activeKeyId();
		}

		@Override
		public WrappedKey wrap(byte[] plaintextKey, Map<String, String> context) {
			wraps.incrementAndGet();
			return delegate.wrap(plaintextKey, context);
		}

		@Override
		public byte[] unwrap(WrappedKey wrappedKey, Map<String, String> context) {
			unwraps.incrementAndGet();
			return delegate.unwrap(wrappedKey, context);
		}
	}
}
