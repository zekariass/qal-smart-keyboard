package com.qalsmartkeyboard.languages.pack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SemVer precedence, strict Ed25519, key rings, key endorsements and the licence allow-list. */
class PackPrimitivesTest {

	static final HexFormat HEX = HexFormat.of();

	static byte[] seed(int fill) {
		byte[] s = new byte[32];
		Arrays.fill(s, (byte) fill);
		return s;
	}

	// ------------------------------------------------------------------ SemVer (the core's semver rules)

	@ParameterizedTest
	@ValueSource(strings = { "1.3", "v1.3.0", "01.3.0", "1.03.0", "1.3.0+build.5", " 1.3.0", "1.3.0 ", "1.0.0-",
			"1.0.0-01", "1.0.0-a..b", "1.0.0-a_b", "18446744073709551616.0.0", "" })
	void invalidVersions(String v) {
		assertThat(SemVer.parse(v)).isEmpty();
	}

	@Test
	void versionPrecedence() {
		List<String> ordered = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
				"1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.9.0", "1.10.0", "2.0.0", "18446744073709551615.0.0");
		for (int i = 0; i + 1 < ordered.size(); i++) {
			SemVer a = SemVer.parse(ordered.get(i)).orElseThrow();
			SemVer b = SemVer.parse(ordered.get(i + 1)).orElseThrow();
			assertThat(a).as(a + " < " + b).isLessThan(b);
		}
		assertThat(SemVer.parse("1.0.0-" + "a".repeat(34))).isPresent();
		assertThat(SemVer.parse("1.0.0-" + "a".repeat(35))).isEmpty(); // 41 characters
		assertThat(SemVer.parse("1.2.3").orElseThrow()).isEqualTo(SemVer.parse("1.2.3").orElseThrow());
	}

	// ------------------------------------------------------------------ Ed25519 strictness

	@Test
	void smallOrderAndNonCanonicalKeysAreNotUsable() {
		byte[] identity = new byte[32];
		identity[0] = 1;
		assertThat(Ed25519.isValidPublicKey(identity)).isFalse();
		assertThat(Ed25519.isValidPublicKey(new byte[32])).isFalse(); // y = 0: order 4
		byte[] minusOne = HEX.parseHex("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f");
		assertThat(Ed25519.isValidPublicKey(minusOne)).isFalse(); // order 2
		byte[] order8 = HEX.parseHex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05");
		assertThat(Ed25519.isValidPublicKey(order8)).isFalse();
		byte[] nonCanonical = HEX.parseHex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"); // y = p
		assertThat(Ed25519.isValidPublicKey(nonCanonical)).isFalse();
		assertThat(Ed25519.isValidPublicKey(new LocalEd25519PackSigner("k", seed(7)).publicKey())).isTrue();
	}

	@Test
	void strictVerificationRejectsMalleableAndSmallOrderSignatures() {
		LocalEd25519PackSigner signer = new LocalEd25519PackSigner("k", seed(9));
		byte[] message = "message".getBytes(StandardCharsets.UTF_8);
		byte[] sig = signer.sign(message);
		assertThat(Ed25519.verifyStrict(signer.publicKey(), message, sig)).isTrue();
		// S + L: same group element, non-canonical scalar
		byte[] malleable = sig.clone();
		BigInteger l = BigInteger.TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
		byte[] s = Arrays.copyOfRange(sig, 32, 64);
		byte[] be = new byte[32];
		for (int i = 0; i < 32; i++) {
			be[i] = s[31 - i];
		}
		byte[] sum = new BigInteger(1, be).add(l).toByteArray();
		byte[] le = new byte[32];
		for (int i = 0; i < 32 && i < sum.length; i++) {
			le[i] = sum[sum.length - 1 - i];
		}
		System.arraycopy(le, 0, malleable, 32, 32);
		assertThat(Ed25519.verifyStrict(signer.publicKey(), message, malleable)).isFalse();
		// R = identity
		byte[] smallR = sig.clone();
		Arrays.fill(smallR, 0, 32, (byte) 0);
		smallR[0] = 1;
		assertThat(Ed25519.verifyStrict(signer.publicKey(), message, smallR)).isFalse();
		assertThat(Ed25519.verifyStrict(signer.publicKey(), message, Arrays.copyOf(sig, 63))).isFalse();
		assertThat(Ed25519.verifyStrict(new byte[31], message, sig)).isFalse();
	}

	@Test
	void keyRingRefusesAliasesDuplicatesAndBadKeys() {
		byte[] pk = new LocalEd25519PackSigner("a", seed(1)).publicKey();
		assertThatThrownBy(() -> KeyRing.of(new TrustedKey("a", pk, KeyStatus.ACTIVE),
				new TrustedKey("b", pk, KeyStatus.REVOKED))).hasMessageContaining("listed twice");
		assertThatThrownBy(() -> KeyRing.of(new TrustedKey("a", pk, KeyStatus.ACTIVE),
				new TrustedKey("a", new LocalEd25519PackSigner("a", seed(2)).publicKey(), KeyStatus.ACTIVE)))
				.hasMessageContaining("duplicate");
		assertThatThrownBy(() -> KeyRing.of(new TrustedKey("bad id", pk, KeyStatus.ACTIVE)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> KeyRing.of(new TrustedKey("a", new byte[32], KeyStatus.ACTIVE)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------ signers and endorsements

	@Test
	void endorsementsAreDomainSeparatedAndBindIdAndKey() {
		LocalEd25519PackSigner current = new LocalEd25519PackSigner("packs-2026-01", seed(3));
		byte[] next = new LocalEd25519PackSigner("packs-2026-02", seed(4)).publicKey();
		String endorsement = KeyEndorsement.sign(current, "packs-2026-02", next);
		assertThat(KeyEndorsement.verify(current.publicKey(), "packs-2026-02", next, endorsement)).isTrue();
		assertThat(KeyEndorsement.verify(current.publicKey(), "packs-2026-03", next, endorsement)).isFalse();
		assertThat(KeyEndorsement.verify(current.publicKey(), "packs-2026-02", current.publicKey(), endorsement)).isFalse();
		assertThat(KeyEndorsement.verify(next, "packs-2026-02", next, endorsement)).isFalse();
		assertThat(KeyEndorsement.verify(current.publicKey(), "packs-2026-02", next, "not base64!")).isFalse();
		// message layout: context, length-prefixed id, raw key
		byte[] message = KeyEndorsement.message("ab", next);
		assertThat(new String(message, 0, 23, StandardCharsets.US_ASCII)).isEqualTo("QAL-KEY-ENDORSEMENT-v1\0");
		assertThat(message[23]).isEqualTo((byte) 2);
		assertThat(Arrays.copyOfRange(message, 26, 58)).isEqualTo(next);
		// a pack signature never verifies as an endorsement (different context)
		byte[] packSignature = current.sign(PackFormat.signingInput(message));
		assertThat(Ed25519.verifyStrict(current.publicKey(), message, packSignature)).isFalse();
	}

	@Test
	void kmsSignerUsesTheClientAndChecksWhatItReturns() {
		LocalEd25519PackSigner inKms = new LocalEd25519PackSigner("x", seed(5));
		KmsSigningClient client = new KmsSigningClient() {
			@Override
			public byte[] publicKey(String keyName) {
				return inKms.publicKey();
			}

			@Override
			public byte[] signEd25519(String keyName, byte[] message) {
				return inKms.sign(message);
			}
		};
		KmsPackSigner kms = new KmsPackSigner(client, "projects/p/keys/packs", "packs-kms-1");
		assertThat(kms.publicKey()).isEqualTo(inKms.publicKey());
		byte[] message = "m".getBytes(StandardCharsets.UTF_8);
		assertThat(Ed25519.verifyStrict(kms.publicKey(), message, kms.sign(message))).isTrue();
		KmsSigningClient lying = new KmsSigningClient() {
			@Override
			public byte[] publicKey(String keyName) {
				return new LocalEd25519PackSigner("y", seed(6)).publicKey();
			}

			@Override
			public byte[] signEd25519(String keyName, byte[] m) {
				return inKms.sign(m);
			}
		};
		assertThatThrownBy(() -> new KmsPackSigner(lying, "k", "packs-kms-1")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new UnconfiguredPackSigner().sign(message)).isInstanceOf(IllegalStateException.class);
	}

	// ------------------------------------------------------------------ licence allow-list

	static LicenseAllowList classpathAllowList() throws IOException {
		try (InputStream in = PackPrimitivesTest.class.getResourceAsStream("/languages/licenses.json")) {
			return LicenseAllowList.load(in);
		}
	}

	/** One source of truth: the backend's classpath copy must equal {@code language-data/licenses.json}. */
	@Test
	void classpathAllowListIsIdenticalToTheLanguageDataFile() throws IOException {
		String languageData = Files.readString(Path.of("..", "language-data", "licenses.json"), StandardCharsets.UTF_8);
		String classpath;
		try (InputStream in = PackPrimitivesTest.class.getResourceAsStream("/languages/licenses.json")) {
			classpath = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertThat(classpath.replace("\r\n", "\n")).as(
				"copy language-data/licenses.json to backend/src/main/resources/languages/licenses.json")
				.isEqualTo(languageData.replace("\r\n", "\n"));
	}

	@Test
	void allowListRulesMatchTheBuildScript() throws IOException {
		LicenseAllowList list = classpathAllowList();
		assertThat(list.check("MIT")).isEqualTo(LicenseAllowList.Verdict.ALLOWED);
		assertThat(list.check("LicenseRef-Qal-Original")).isEqualTo(LicenseAllowList.Verdict.ALLOWED);
		assertThat(list.check(null)).isEqualTo(LicenseAllowList.Verdict.MISSING);
		assertThat(list.check(" ")).isEqualTo(LicenseAllowList.Verdict.MISSING);
		assertThat(list.check("WTFPL")).isEqualTo(LicenseAllowList.Verdict.UNKNOWN); // unknown fails closed
		assertThat(list.check("mit")).isEqualTo(LicenseAllowList.Verdict.UNKNOWN); // exact codes
		assertThat(list.check("CC-BY-NC-4.0")).isEqualTo(LicenseAllowList.Verdict.NOT_COMMERCIAL);
		assertThat(list.check("PROPRIETARY")).isEqualTo(LicenseAllowList.Verdict.NOT_COMMERCIAL);
		assertThat(list.check("UNKNOWN")).isEqualTo(LicenseAllowList.Verdict.NOT_COMMERCIAL);
		assertThat(list.entry("CC0-1.0").orElseThrow().consentBasis()).isEqualTo("PUBLIC_DOMAIN");
	}

	@Test
	void anNcCodeIsRefusedEvenIfListedAsCommercial() throws IOException {
		String doc = "{\"format\":\"qal-licence-allowlist\",\"formatVersion\":1,\"licenses\":{"
				+ "\"LicenseRef-Foo-NC\":{\"commercialUse\":true},\"LicenseRef-NCX\":{\"commercialUse\":true},"
				+ "\"Bar\":{\"commercialUse\":\"yes\"}}}";
		LicenseAllowList list = LicenseAllowList.load(new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)));
		assertThat(list.check("LicenseRef-Foo-NC")).isEqualTo(LicenseAllowList.Verdict.NOT_COMMERCIAL);
		assertThat(list.check("LicenseRef-NCX")).isEqualTo(LicenseAllowList.Verdict.ALLOWED); // NC must be a component
		assertThat(list.check("Bar")).isEqualTo(LicenseAllowList.Verdict.NOT_COMMERCIAL); // only literal true counts
		assertThatThrownBy(() -> LicenseAllowList.load(new ByteArrayInputStream("{\"format\":\"x\"}".getBytes())))
				.isInstanceOf(IOException.class);
	}

	@Test
	void profileIdOfRulesArtifacts() throws IOException {
		byte[] draft = Files.readAllBytes(Path.of("..", "language-data", "profiles", "am-phonetic-draft.json"));
		assertThat(TransliterationProfileArtifact.profileId(draft)).contains("am-phonetic"); // pre-D21 id
		assertThat(TransliterationProfileArtifact.profileId("{\"format\":\"other\",\"profileId\":\"X\"}".getBytes()))
				.isEmpty();
		assertThat(TransliterationProfileArtifact.profileId("not json".getBytes())).isEmpty();
	}
}
