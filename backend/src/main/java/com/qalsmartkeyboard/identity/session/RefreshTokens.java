package com.qalsmartkeyboard.identity.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque refresh tokens (SD 9.3): 256 random bits, base64url without padding (43 characters). Stored only as the hex
 * SHA-256 of the token ({@code user_sessions.refresh_token_hash}); an unkeyed hash is enough for 256-bit random values.
 */
final class RefreshTokens {

	static final int TOKEN_BYTES = 32;

	private final SecureRandom random;

	RefreshTokens(SecureRandom random) {
		this.random = random;
	}

	String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
