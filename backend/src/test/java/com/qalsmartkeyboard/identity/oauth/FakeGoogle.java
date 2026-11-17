package com.qalsmartkeyboard.identity.oauth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * A fake Google for tests: a loopback HTTP server that serves a JWKS (no network beyond 127.0.0.1) and builds ID tokens
 * signed with its keys. {@link #fetches()} counts how often the JWKS was requested.
 */
public final class FakeGoogle implements AutoCloseable {

	public static final String CLIENT_ID = "test-web-client.apps.googleusercontent.com";
	public static final String ISSUER = "https://accounts.google.com";

	private final HttpServer server;
	private final AtomicInteger fetches = new AtomicInteger();
	private volatile List<RSAKey> published = List.of();
	private volatile int status = 200;

	public FakeGoogle() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/certs", exchange -> {
			fetches.incrementAndGet();
			byte[] body = new JWKSet(published.stream().map(RSAKey::toPublicJWK).map(k -> (com.nimbusds.jose.jwk.JWK) k)
					.toList()).toString().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
			if (status == 200) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
			exchange.close();
		});
		server.start();
	}

	public static RSAKey newKey(String kid) {
		try {
			return new RSAKeyGenerator(2048).keyID(kid).keyUse(KeyUse.SIGNATURE).generate();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	public URI jwksUri() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/certs");
	}

	/** Replaces the published key set (key rotation). */
	public void publish(RSAKey... keys) {
		published = List.of(keys);
	}

	public void respondWith(int status) {
		this.status = status;
	}

	public int fetches() {
		return fetches.get();
	}

	/** Claims of a valid token for {@code subject}, issued now and valid for an hour. */
	public static JWTClaimsSet.Builder claims(String subject, String nonce) {
		Instant now = Instant.now();
		return new JWTClaimsSet.Builder().issuer(ISSUER).audience(CLIENT_ID).subject(subject)
				.issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(3600))).claim("nonce", nonce)
				.claim("email", subject + "@example.test").claim("email_verified", true).claim("name", "Test User");
	}

	public static String sign(RSAKey key, JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(
					new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(com.nimbusds.jose.JOSEObjectType.JWT)
							.build(),
					claims);
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
