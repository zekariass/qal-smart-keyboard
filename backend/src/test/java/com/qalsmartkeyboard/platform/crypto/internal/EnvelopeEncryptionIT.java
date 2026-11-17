package com.qalsmartkeyboard.platform.crypto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.KeyManagementException;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.crypto.kms.KeyManagementService;

/**
 * Plan step B1.5 "Done when: round-trip + shred test", against PostgreSQL 18 with the V1 schema and the
 * application's own wiring (local dev KMS with an ephemeral KEK from test config/application.yaml).
 * Test methods run without a surrounding transaction so commits and races are real.
 */
@SpringBootTest
@Testcontainers
class EnvelopeEncryptionIT {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(System.getProperty("qal.test.postgres-image", "postgres:18")));

	private static final String TEXT = "ሰላም ለዓለም — Selam";

	@Autowired
	UserDataEncryption encryption;

	@Autowired
	KeyManagementService kms;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JdbcClient jdbcClient;

	@Autowired
	PlatformTransactionManager transactionManager;

	@AfterEach
	void clearCache() {
		((EnvelopeEncryptionService) encryption).clearCache();
	}

	/** A minimal valid users row (all identity columns nullable; status defaults to PENDING). */
	private UUID newUser() {
		return jdbc.queryForObject("INSERT INTO users (account_status) VALUES ('ACTIVE') RETURNING id", UUID.class);
	}

	/** A second application instance: same database and KMS, its own DEK cache. */
	private EnvelopeEncryptionService otherInstance() {
		return new EnvelopeEncryptionService(new JdbcUserDataKeyStore(jdbcClient), kms,
				new DataKeyCache(100, Duration.ofMinutes(5), InstantSource.system()), transactionManager,
				new SecureRandom());
	}

	private List<Map<String, Object>> keyRows(UUID user) {
		return jdbc.queryForList("""
				SELECT key_version, key_status, wrapped_dek, kms_key_id, rotated_at, destroyed_at
				FROM user_data_keys WHERE user_id = ? ORDER BY key_version""", user);
	}

	@Test
	void roundTripThroughARealCiphertextColumn() {
		UUID user = newUser();
		UUID profileId = UUID.randomUUID();
		EncryptionContext label = EncryptionContext.of("user_destination_profiles", "label_ciphertext", profileId);

		EncryptedValue sealed = encryption.encryptString(user, label, TEXT);
		jdbc.update("""
				INSERT INTO user_destination_profiles (id, user_id, label_ciphertext, key_version, destination_language_id)
				VALUES (?, ?, ?, ?, (SELECT id FROM supported_languages WHERE language_code = 'am'))""",
				profileId, user, sealed.ciphertext(), sealed.keyVersion());

		Map<String, Object> stored = jdbc.queryForMap(
				"SELECT label_ciphertext, key_version FROM user_destination_profiles WHERE id = ?", profileId);
		assertThat(stored.get("key_version")).isEqualTo(1);
		assertThat(encryption.decryptString(user, label, (byte[]) stored.get("label_ciphertext"))).isEqualTo(TEXT);
		clearCache(); // force the KMS unwrap path
		assertThat(encryption.decryptString(user, label, (byte[]) stored.get("label_ciphertext"))).isEqualTo(TEXT);
		assertThat(otherInstance().decryptString(user, label, sealed.ciphertext())).isEqualTo(TEXT);

		assertThat(keyRows(user)).singleElement().satisfies(row -> {
			assertThat(row.get("key_version")).isEqualTo(1);
			assertThat(row.get("key_status")).isEqualTo("ACTIVE");
			assertThat((byte[]) row.get("wrapped_dek")).hasSize(61);
			assertThat((String) row.get("kms_key_id")).startsWith("local:ephemeral-");
			assertThat(row.get("destroyed_at")).isNull();
		});
	}

	@Test
	void ciphertextMovedToAnotherRowOrUserIsRejected() {
		UUID alice = newUser();
		UUID bob = newUser();
		EncryptionContext row1 = EncryptionContext.of("user_history_items", "input_ciphertext", UUID.randomUUID());
		EncryptionContext row2 = EncryptionContext.of("user_history_items", "input_ciphertext", UUID.randomUUID());
		byte[] ct = encryption.encryptString(alice, row1, TEXT).ciphertext();
		encryption.encryptString(bob, row1, "bob has a key too");

		assertThatThrownBy(() -> encryption.decrypt(alice, row2, ct)).isInstanceOf(CiphertextRejectedException.class);
		assertThatThrownBy(() -> encryption.decrypt(bob, row1, ct)).isInstanceOf(CiphertextRejectedException.class);
	}

	@Test
	void shredMakesDataUnreadableOnEveryInstanceAndIsIdempotent() {
		UUID user = newUser();
		EncryptionContext ctx = EncryptionContext.of("user_history_items", "output_ciphertext", UUID.randomUUID());
		byte[] ct = encryption.encryptString(user, ctx, TEXT).ciphertext();
		EnvelopeEncryptionService other = otherInstance();
		assertThat(other.decryptString(user, ctx, ct)).isEqualTo(TEXT); // other instance now has the DEK cached

		assertThat(encryption.cryptoShred(user)).isEqualTo(1);

		assertThatThrownBy(() -> encryption.decrypt(user, ctx, ct)).isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> other.decrypt(user, ctx, ct)).isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> encryption.encryptString(user, ctx, "new")).isInstanceOf(CryptoShreddedException.class);
		assertThatThrownBy(() -> encryption.rotateKey(user)).isInstanceOf(CryptoShreddedException.class);
		assertThat(encryption.isShredded(user)).isTrue();

		Map<String, Object> row = keyRows(user).getFirst();
		assertThat(row.get("key_status")).isEqualTo("DESTROYED");
		assertThat(row.get("wrapped_dek")).isNull();
		assertThat(row.get("destroyed_at")).isNotNull();
		Timestamp destroyedAt = (Timestamp) row.get("destroyed_at");

		assertThat(encryption.cryptoShred(user)).isZero();
		assertThat(keyRows(user)).singleElement()
				.satisfies(again -> assertThat(again.get("destroyed_at")).isEqualTo(destroyedAt));
	}

	@Test
	void shredDestroysEveryVersion() {
		UUID user = newUser();
		encryption.encryptString(user, EncryptionContext.of("t", "c", "1"), TEXT);
		encryption.rotateKey(user);
		encryption.rotateKey(user);

		assertThat(encryption.cryptoShred(user)).isEqualTo(3);
		assertThat(keyRows(user)).hasSize(3).allSatisfy(row -> {
			assertThat(row.get("key_status")).isEqualTo("DESTROYED");
			assertThat(row.get("wrapped_dek")).isNull();
		});
	}

	@Test
	void shreddingAUserWithoutKeysLeavesAMarkerThatBlocksNewKeys() {
		UUID user = newUser();
		assertThat(encryption.cryptoShred(user)).isZero();

		assertThat(keyRows(user)).singleElement().satisfies(row -> {
			assertThat(row.get("key_status")).isEqualTo("DESTROYED");
			assertThat(row.get("kms_key_id")).isEqualTo(JdbcUserDataKeyStore.SHRED_MARKER_KMS_KEY_ID);
		});
		assertThatThrownBy(() -> encryption.encryptString(user, EncryptionContext.of("t", "c", "1"), "x"))
				.isInstanceOf(CryptoShreddedException.class);
		assertThat(encryption.cryptoShred(UUID.randomUUID())).isZero(); // unknown user: no-op, no FK error
	}

	@Test
	void shredJoinsTheCallersTransaction() {
		UUID user = newUser();
		EncryptionContext ctx = EncryptionContext.of("t", "c", "1");
		byte[] ct = encryption.encryptString(user, ctx, TEXT).ciphertext();

		new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
			assertThat(encryption.cryptoShred(user)).isEqualTo(1);
			assertThatThrownBy(() -> encryption.encryptString(user, ctx, "x"))
					.isInstanceOf(CryptoShreddedException.class); // sees its own uncommitted shred
			tx.setRollbackOnly(); // e.g. the rest of the account deletion failed
		});

		assertThat(encryption.isShredded(user)).isFalse();
		assertThat(encryption.decryptString(user, ctx, ct)).isEqualTo(TEXT);
	}

	@Test
	void schemaGuardsBehindTheShredSemantics() {
		UUID user = newUser();
		encryption.encryptString(user, EncryptionContext.of("t", "c", "1"), TEXT);

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO user_data_keys (user_id, key_version, wrapped_dek, kms_key_id)
				VALUES (?, 2, '\\x00'::bytea, 'local:x')""", user))
				.as("ux_user_data_keys_one_active").isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE user_data_keys SET key_status = 'DESTROYED', destroyed_at = now() WHERE user_id = ?", user))
				.as("ck_user_data_key_destroyed: wrapped_dek must be erased").isInstanceOf(
						DataIntegrityViolationException.class);

		encryption.cryptoShred(user);
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE user_data_keys SET wrapped_dek = '\\x00'::bytea WHERE user_id = ?", user))
				.as("ck_user_data_key_destroyed: no key material on a destroyed row")
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"UPDATE user_data_keys SET key_status = 'ACTIVE' WHERE user_id = ?", user))
				.as("ck_user_data_key_destroyed: a destroyed key cannot be reactivated without key material")
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rotationKeepsOldCiphertextReadable() {
		UUID user = newUser();
		EncryptionContext ctx = EncryptionContext.of("user_history_items", "input_ciphertext", UUID.randomUUID());
		byte[] v1 = encryption.encryptString(user, ctx, "old").ciphertext();

		assertThat(encryption.rotateKey(user)).isEqualTo(2);
		EncryptedValue v2 = encryption.encryptString(user, ctx, "new");
		clearCache();

		assertThat(v2.keyVersion()).isEqualTo(2);
		assertThat(encryption.decryptString(user, ctx, v1)).isEqualTo("old");
		assertThat(encryption.decryptString(user, ctx, v2.ciphertext())).isEqualTo("new");
		List<Map<String, Object>> rows = keyRows(user);
		assertThat(rows).extracting(r -> r.get("key_status")).containsExactly("ROTATED", "ACTIVE");
		assertThat(rows.getFirst().get("rotated_at")).isNotNull();
		assertThat(rows.getFirst().get("wrapped_dek")).isNotNull();
	}

	@Test
	void concurrentFirstUseOnTwoInstancesCreatesOneKey() throws Exception {
		UUID user = newUser();
		List<UserDataEncryption> instances = List.of(encryption, otherInstance());
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<EncryptedValue>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				UserDataEncryption instance = instances.get(i % 2);
				EncryptionContext ctx = EncryptionContext.of("t", "c", Integer.toString(i));
				Callable<EncryptedValue> task = () -> {
					start.await();
					return instance.encryptString(user, ctx, TEXT);
				};
				results.add(pool.submit(task));
			}
			start.countDown();
			for (int i = 0; i < threads; i++) {
				EncryptedValue value = results.get(i).get(60, TimeUnit.SECONDS);
				assertThat(value.keyVersion()).isEqualTo(1);
				EncryptionContext ctx = EncryptionContext.of("t", "c", Integer.toString(i));
				assertThat(instances.get((i + 1) % 2).decryptString(user, ctx, value.ciphertext())).isEqualTo(TEXT);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(keyRows(user)).singleElement().satisfies(row -> assertThat(row.get("key_status")).isEqualTo("ACTIVE"));
	}

	@Test
	void concurrentRotationAndShredLeaveNoUsableKey() throws Exception {
		UUID user = newUser();
		encryption.encryptString(user, EncryptionContext.of("t", "c", "1"), TEXT);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<?>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				boolean shred = i == 3;
				UserDataEncryption instance = i % 2 == 0 ? encryption : otherInstance();
				results.add(pool.submit(() -> {
					start.await();
					try {
						return shred ? instance.cryptoShred(user) : instance.rotateKey(user);
					} catch (CryptoShreddedException expectedAfterShred) {
						return -1;
					}
				}));
			}
			start.countDown();
			for (Future<?> result : results) {
				result.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(keyRows(user)).isNotEmpty().allSatisfy(row -> {
			assertThat(row.get("key_status")).isEqualTo("DESTROYED");
			assertThat(row.get("wrapped_dek")).isNull();
		});
	}

	@Test
	void wrappedDekCopiedToAnotherUsersRowIsRejected() {
		UUID alice = newUser();
		UUID bob = newUser();
		EncryptionContext ctx = EncryptionContext.of("t", "c", "1");
		encryption.encryptString(alice, ctx, "alice");
		byte[] bobCt = encryption.encryptString(bob, ctx, "bob").ciphertext();

		jdbc.update("""
				UPDATE user_data_keys SET wrapped_dek = (SELECT wrapped_dek FROM user_data_keys WHERE user_id = ?)
				WHERE user_id = ?""", alice, bob);
		clearCache();

		assertThatThrownBy(() -> encryption.decrypt(bob, ctx, bobCt)).isInstanceOf(KeyManagementException.class);
		assertThatThrownBy(() -> encryption.encryptString(bob, ctx, "x")).isInstanceOf(KeyManagementException.class);
	}

	@Test
	void unknownUserGetsNoKey() {
		UUID ghost = UUID.randomUUID();
		assertThatThrownBy(() -> encryption.encryptString(ghost, EncryptionContext.of("t", "c", "1"), "x"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(keyRows(ghost)).isEmpty();
	}
}
