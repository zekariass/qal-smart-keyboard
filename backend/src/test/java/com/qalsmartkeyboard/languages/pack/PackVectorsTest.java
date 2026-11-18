package com.qalsmartkeyboard.languages.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Java verifier and signer against the shared cross-language vectors {@code core/tests/vectors/pack-v1/*.json}
 * (generated and checked by the Rust core, independently reproduced with OpenSSL): every vector's {@code verifies}
 * outcome and reject reason, the verified pack info, and byte-for-byte signer output wherever the vector has an
 * {@code input}. The load-only policy (deltas) is the publisher's, see the last check.
 */
class PackVectorsTest {

	static final Path VECTORS = Path.of("..", "core", "tests", "vectors", "pack-v1");
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final HexFormat HEX = HexFormat.of();

	static Stream<Path> vectors() throws IOException {
		try (Stream<Path> files = Files.list(VECTORS)) {
			return files.filter(p -> p.toString().endsWith(".json")).sorted().toList().stream();
		}
	}

	static JsonNode read(Path p) {
		try {
			return JSON.readTree(Files.readString(p, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static KeyRing keyRing(JsonNode v) {
		List<TrustedKey> keys = new ArrayList<>();
		for (JsonNode k : v.get("trustedKeys")) {
			keys.add(new TrustedKey(k.get("keyId").asString(), HEX.parseHex(k.get("publicKeyHex").asString()),
					KeyStatus.valueOf(k.get("status").asString())));
		}
		return new KeyRing(keys);
	}

	static PackLimits limits(JsonNode v) {
		JsonNode l = v.get("limits");
		return new PackLimits(l.get("maxContainerBytes").asLong(), l.get("maxManifestBytes").asLong(),
				l.get("maxArtifactBytes").asLong());
	}

	@Test
	void allTwentyVectorsArePresent() throws IOException {
		assertThat(vectors().count()).isEqualTo(20);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("vectors")
	void verifierMatchesTheCore(Path file) {
		JsonNode v = read(file);
		assertThat(v.get("format").asString()).isEqualTo("qal-pack-v1-test-vector");
		byte[] container = HEX.parseHex(v.get("containerHex").asString());
		JsonNode expected = v.get("expected");
		if (expected.get("verifies").asBoolean()) {
			VerifiedPack pack = PackVerifier.verify(container, keyRing(v), limits(v));
			PackManifest m = pack.manifest();
			// The core's load step additionally refuses deltas; the publisher refuses them too (DELTA_NOT_SUPPORTED).
			if ("REJECT".equals(expected.get("load").asString())) {
				assertThat(expected.get("reason").asString()).isEqualTo("DELTA_NOT_SUPPORTED");
				assertThat(m.isDelta()).isTrue();
				return;
			}
			JsonNode info = expected.get("packInfo");
			assertThat(m.resourceId()).isEqualTo(info.get("packId").asString());
			assertThat(m.languageCode()).isEqualTo(info.get("languageCode").asString());
			assertThat(m.resourceType()).isEqualTo(info.get("resourceType").asString());
			assertThat(m.version().toString()).isEqualTo(info.get("version").asString());
			assertThat(m.platform()).isEqualTo(info.get("platform").asString());
			assertThat(m.releaseChannel()).isEqualTo(info.get("releaseChannel").asString());
			assertThat(m.sizeBytes().longValueExact()).isEqualTo(info.get("sizeBytes").asLong());
			assertThat(m.sha256()).isEqualTo(info.get("sha256").asString());
			assertThat(m.isDelta()).isEqualTo(info.get("isDelta").asBoolean());
			assertThat(pack.key().keyId()).isEqualTo(info.get("keyId").asString());
			JsonNode min = info.get("minAppVersion");
			assertThat(m.minAppVersion() == null ? null : m.minAppVersion().toString())
					.isEqualTo(min == null || min.isNull() ? null : min.asString());
			assertThat(pack.artifactLength()).isEqualTo(m.sizeBytes().intValueExact());
		}
		else {
			assertThatThrownBy(() -> PackVerifier.verify(container, keyRing(v), limits(v)))
					.isInstanceOfSatisfying(PackRejectedException.class, e -> assertThat(e.reason().name())
							.isEqualTo(expected.get("reason").asString()));
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("vectors")
	void signerReproducesTheContainerByteForByte(Path file) {
		JsonNode v = read(file);
		JsonNode input = v.get("input");
		if (input == null || input.isNull()) {
			return; // a mutated container: no signer produces it
		}
		PackSigner signer = new LocalEd25519PackSigner(input.get("signingKeyId").asString(),
				HEX.parseHex(input.get("signingSeedHex").asString()));
		byte[] container = PackWriter.sign(signer, input.get("manifestUtf8").asString().getBytes(StandardCharsets.UTF_8),
				HEX.parseHex(input.get("artifactHex").asString()));
		assertThat(HEX.formatHex(container)).isEqualTo(v.get("containerHex").asString());
	}

	@Test
	void seedsDeriveTheVectorPublicKeys() {
		JsonNode v = read(VECTORS.resolve("valid-minimal.json"));
		byte[] seed = new byte[32];
		for (int i = 0; i < 32; i++) {
			seed[i] = (byte) i; // test-active-1 = 00 01 .. 1f (spec section 11)
		}
		LocalEd25519PackSigner signer = new LocalEd25519PackSigner("test-active-1", seed);
		assertThat(HEX.formatHex(signer.publicKey()))
				.isEqualTo(v.get("trustedKeys").get(0).get("publicKeyHex").asString());
	}
}
