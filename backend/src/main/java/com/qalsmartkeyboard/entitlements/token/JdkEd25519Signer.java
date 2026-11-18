package com.qalsmartkeyboard.entitlements.token;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Objects;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;

/**
 * A Nimbus {@link JWSSigner} for {@code EdDSA} with an Ed25519 key, backed by the JDK's {@code Signature "Ed25519"}
 * (RFC 8037; Nimbus' own {@code Ed25519Signer} needs Google Tink, which this build does not carry). Ed25519 is
 * deterministic, so the same key, header and payload always give the same signature (checked against the RFC 8037
 * A.4 vector in the tests). A future KMS-backed signer replaces this class (ADR 0013).
 */
final class JdkEd25519Signer implements JWSSigner {

	private final PrivateKey privateKey;
	private final JCAContext context = new JCAContext();

	JdkEd25519Signer(PrivateKey privateKey) {
		this.privateKey = Objects.requireNonNull(privateKey, "privateKey");
		if (!"EdDSA".equals(privateKey.getAlgorithm()) && !"Ed25519".equals(privateKey.getAlgorithm())) {
			throw new IllegalArgumentException("Not an Ed25519 private key");
		}
	}

	@Override
	public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
		if (!JWSAlgorithm.EdDSA.equals(header.getAlgorithm())) {
			throw new JOSEException("Only EdDSA is supported");
		}
		try {
			Signature signature = Signature.getInstance("Ed25519");
			signature.initSign(privateKey);
			signature.update(signingInput);
			return Base64URL.encode(signature.sign());
		}
		catch (GeneralSecurityException e) {
			throw new JOSEException("Ed25519 signing failed", e);
		}
	}

	@Override
	public Set<JWSAlgorithm> supportedJWSAlgorithms() {
		return Set.of(JWSAlgorithm.EdDSA);
	}

	@Override
	public JCAContext getJCAContext() {
		return context;
	}
}
