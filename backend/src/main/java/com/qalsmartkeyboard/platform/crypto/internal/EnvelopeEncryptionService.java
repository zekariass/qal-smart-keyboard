package com.qalsmartkeyboard.platform.crypto.internal;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.AEADBadTagException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException;
import com.qalsmartkeyboard.platform.crypto.CiphertextRejectedException.Reason;
import com.qalsmartkeyboard.platform.crypto.CryptoShreddedException;
import com.qalsmartkeyboard.platform.crypto.EncryptedValue;
import com.qalsmartkeyboard.platform.crypto.EncryptionContext;
import com.qalsmartkeyboard.platform.crypto.KeyManagementException;
import com.qalsmartkeyboard.platform.crypto.UserDataEncryption;
import com.qalsmartkeyboard.platform.crypto.internal.UserDataKeyStore.KeyRecord;
import com.qalsmartkeyboard.platform.crypto.internal.UserDataKeyStore.KeyStatus;
import com.qalsmartkeyboard.platform.crypto.kms.Aes256Gcm;
import com.qalsmartkeyboard.platform.crypto.kms.KeyManagementService;
import com.qalsmartkeyboard.platform.crypto.kms.WrappedKey;

/**
 * {@link UserDataEncryption} with per-user DEKs in {@code user_data_keys}, wrapped by a {@link KeyManagementService}
 * (ADR 0005).
 *
 * <p>Key rows are read on every operation (in the caller's transaction, so its own uncommitted shred is
 * seen), which makes a shred effective on every instance at once; {@link DataKeyCache} only saves KMS unwraps.
 * Creating or rotating a key wraps the new DEK first (KMS call outside the database transaction), then, in a
 * new transaction holding a per-user advisory lock, re-checks the state and writes. A lost race (another
 * instance created the key first) is detected by that re-check or, defensively, by the unique indexes, and is
 * resolved by re-reading.
 */
final class EnvelopeEncryptionService implements UserDataEncryption {

	private static final int MAX_ATTEMPTS = 5;

	/** Context the DEK is wrapped under: a wrapped DEK only unwraps for the same user and version. */
	static Map<String, String> wrapContext(UUID userId, int keyVersion) {
		return Map.of(
				"purpose", "qal.user-data-key",
				"userId", userId.toString(),
				"keyVersion", Integer.toString(keyVersion));
	}

	private final UserDataKeyStore store;
	private final KeyManagementService kms;
	private final DataKeyCache cache;
	private final SecureRandom random;
	private final TransactionTemplate newTransaction;
	private final TransactionTemplate joinTransaction;

	EnvelopeEncryptionService(UserDataKeyStore store, KeyManagementService kms, DataKeyCache cache,
			PlatformTransactionManager transactionManager, SecureRandom random) {
		this.store = store;
		this.kms = kms;
		this.cache = cache;
		this.random = random;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.joinTransaction = new TransactionTemplate(transactionManager);
		this.joinTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
	}

	@Override
	public EncryptedValue encrypt(UUID userId, EncryptionContext context, byte[] plaintext) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(plaintext, "plaintext");
		KeyState state = KeyState.of(store.findAll(userId));
		if (state.shredded()) {
			throw new CryptoShreddedException();
		}
		KeyRecord active = state.active() != null ? state.active() : newKey(userId, false);
		byte[] dek = dataKey(userId, active);
		try {
			byte[] nonce = new byte[Aes256Gcm.NONCE_BYTES];
			random.nextBytes(nonce);
			byte[] aad = CiphertextFormat.associatedData(userId, active.keyVersion(), context);
			byte[] body = Aes256Gcm.seal(dek, nonce, aad, plaintext);
			return new EncryptedValue(active.keyVersion(), CiphertextFormat.encode(active.keyVersion(), nonce, body));
		} finally {
			Arrays.fill(dek, (byte) 0);
		}
	}

	@Override
	public byte[] decrypt(UUID userId, EncryptionContext context, byte[] ciphertext) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(context, "context");
		CiphertextFormat.Header header = CiphertextFormat.parse(ciphertext);
		KeyRecord key = store.find(userId, header.keyVersion())
				.orElseThrow(() -> new CiphertextRejectedException(Reason.UNKNOWN_KEY_VERSION));
		if (key.status() == KeyStatus.DESTROYED) {
			cache.evictUser(userId);
			throw new CryptoShreddedException();
		}
		byte[] dek = dataKey(userId, key);
		try {
			byte[] aad = CiphertextFormat.associatedData(userId, header.keyVersion(), context);
			return Aes256Gcm.open(dek, header.nonce(), aad, ciphertext, CiphertextFormat.HEADER_BYTES,
					ciphertext.length - CiphertextFormat.HEADER_BYTES);
		} catch (AEADBadTagException e) {
			throw new CiphertextRejectedException(Reason.AUTHENTICATION_FAILED);
		} finally {
			Arrays.fill(dek, (byte) 0);
		}
	}

	@Override
	public int rotateKey(UUID userId) {
		Objects.requireNonNull(userId, "userId");
		return newKey(userId, true).keyVersion();
	}

	@Override
	public int cryptoShred(UUID userId) {
		Objects.requireNonNull(userId, "userId");
		cache.evictUser(userId);
		Integer destroyed = joinTransaction.execute(tx -> {
			store.lockUser(userId);
			int count = store.destroyAll(userId);
			if (count == 0 && store.findAll(userId).isEmpty()) {
				store.insertShredMarker(userId);
			}
			return count;
		});
		cache.evictUser(userId);
		return destroyed == null ? 0 : destroyed;
	}

	@Override
	public boolean isShredded(UUID userId) {
		return KeyState.of(store.findAll(Objects.requireNonNull(userId, "userId"))).shredded();
	}

	/** Drops every cached data key (tests, shutdown). */
	void clearCache() {
		cache.clear();
	}

	/**
	 * Creates the user's next key version. With {@code rotate == false} an existing ACTIVE key is returned
	 * instead (lazy first use); with {@code rotate == true} the ACTIVE key becomes ROTATED.
	 */
	private KeyRecord newKey(UUID userId, boolean rotate) {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			// First read in the caller's transaction (sees its own uncommitted shred); retries read the latest
			// committed state, in case the caller's snapshot is older than a concurrent rotation.
			KeyState before = attempt == 0
					? KeyState.of(store.findAll(userId))
					: newTransaction.execute(tx -> KeyState.of(store.findAll(userId)));
			if (before.shredded()) {
				throw new CryptoShreddedException();
			}
			if (!rotate && before.active() != null) {
				return before.active();
			}
			int version = before.maxVersion() + 1;
			byte[] dek = new byte[Aes256Gcm.KEY_BYTES];
			random.nextBytes(dek);
			try {
				WrappedKey wrapped = kms.wrap(dek, wrapContext(userId, version));
				Created created = newTransaction.execute(tx -> {
					store.lockUser(userId);
					KeyState now = KeyState.of(store.findAll(userId));
					if (now.shredded()) {
						throw new CryptoShreddedException();
					}
					if (now.maxVersion() != before.maxVersion()) {
						// Another caller created or rotated meanwhile; its key is newer than the one this call
						// started from, which is all a first use or a rotation needs.
						return now.active() == null ? null : new Created(now.active(), false);
					}
					if (!rotate && now.active() != null) {
						return new Created(now.active(), false);
					}
					if (rotate) {
						store.markActiveRotated(userId);
					}
					store.insertActive(userId, version, wrapped);
					return new Created(new KeyRecord(version, KeyStatus.ACTIVE, wrapped), true);
				});
				if (created == null) {
					continue;
				}
				if (created.byThisCall()) {
					cache.put(new DataKeyCache.Key(userId, version), dek);
				}
				return created.key();
			} catch (DuplicateKeyException e) {
				// Lost a race the advisory lock should have prevented; the next attempt re-reads the winner.
			} catch (DataIntegrityViolationException e) {
				throw new IllegalArgumentException("No user exists with the given id", e);
			} finally {
				Arrays.fill(dek, (byte) 0);
			}
		}
		throw new IllegalStateException("Could not create a data key: too much concurrent key activity");
	}

	/** Unwrapped DEK of {@code key}; the caller wipes the returned array. */
	private byte[] dataKey(UUID userId, KeyRecord key) {
		DataKeyCache.Key cacheKey = new DataKeyCache.Key(userId, key.keyVersion());
		byte[] cached = cache.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		byte[] dek = kms.unwrap(key.wrapped(), wrapContext(userId, key.keyVersion()));
		if (dek.length != Aes256Gcm.KEY_BYTES) {
			Arrays.fill(dek, (byte) 0);
			throw new KeyManagementException("Unwrapped data key has the wrong length");
		}
		cache.put(cacheKey, dek);
		return dek;
	}

	private record Created(KeyRecord key, boolean byThisCall) {
	}

	private record KeyState(KeyRecord active, boolean shredded, int maxVersion) {

		static KeyState of(List<KeyRecord> rows) {
			KeyRecord active = null;
			boolean shredded = false;
			int max = 0;
			for (KeyRecord row : rows) {
				max = Math.max(max, row.keyVersion());
				if (row.status() == KeyStatus.ACTIVE) {
					active = row;
				} else if (row.status() == KeyStatus.DESTROYED) {
					shredded = true;
				}
			}
			return new KeyState(active, shredded, max);
		}
	}
}
