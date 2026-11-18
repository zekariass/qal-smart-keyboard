package com.qalsmartkeyboard.aiclient

import com.qalsmartkeyboard.aiclient.TestTokens.DEVICE
import com.qalsmartkeyboard.aiclient.TestTokens.SUB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTokenVerifierTest {
    private val key = TestKey("ent-2026-11")
    private val verifier = EntitlementTokenVerifier(key.pinned())
    private val now = 1_800_000_000_000L // ms
    private val iat = now / 1000 - 3600

    private fun verify(jws: String, sub: String? = SUB, device: String? = DEVICE, at: Long = now) = verifier.verify(jws, sub, device, at)

    private fun rejected(v: TokenVerdict): TokenRejection = (v as TokenVerdict.Rejected).reason

    private fun valid() = TestTokens.mint(key, TestTokens.claims(iat))

    @Test fun aGoodTokenVerifies() {
        val t = (verify(valid()) as TokenVerdict.Valid).token
        assertEquals("PREMIUM", t.planCode)
        assertEquals(true, t.features["ON_DEVICE_AI_MODELS"])
        assertEquals(iat * 1000, t.issuedAtMs)
        assertEquals("ent-2026-11", t.keyId)
    }

    @Test fun aTokenForADifferentUserOrDeviceIsRejected() {
        assertEquals(TokenRejection.SUBJECT, rejected(verify(valid(), sub = "someone-else")))
        assertEquals(TokenRejection.SUBJECT, rejected(verify(valid(), sub = null)))
        assertEquals(TokenRejection.DEVICE, rejected(verify(valid(), device = "device-2")))
        assertEquals(TokenRejection.DEVICE, rejected(verify(valid(), device = null)))
        // No deviceId claim: the session need not be bound to a device.
        val unbound = TestTokens.mint(key, TestTokens.claims(iat, device = null))
        assertTrue(verify(unbound, device = null) is TokenVerdict.Valid)
    }

    @Test fun aTamperedPayloadOrSignatureIsRejected() {
        val parts = valid().split('.')
        val forgedClaims = TestTokens.claims(iat, features = """{"ON_DEVICE_AI_MODELS":true,"EXTRA":true}""")
        assertEquals(TokenRejection.BAD_SIGNATURE, rejected(verify(parts[0] + "." + b64(forgedClaims.toByteArray()) + "." + parts[2])))
        val sig = parts[2].toCharArray().also { it[3] = if (it[3] == 'A') 'B' else 'A' }.concatToString()
        assertEquals(TokenRejection.BAD_SIGNATURE, rejected(verify(parts[0] + "." + parts[1] + "." + sig)))
        assertEquals(TokenRejection.BAD_SIGNATURE, rejected(verify(parts[0] + "." + parts[1] + "." + b64(ByteArray(10)))))
    }

    @Test fun aKeyThatIsNotPinnedIsRejectedEvenIfTheSignatureIsValid() {
        val other = TestKey("ent-2026-12")
        // Right signature for its own key, but the build pins only ent-2026-11.
        assertEquals(TokenRejection.UNKNOWN_KEY, rejected(verify(TestTokens.mint(other, TestTokens.claims(iat)))))
        // A pinned kid signed by someone else's key.
        val forged = TestTokens.mint(other, TestTokens.claims(iat), header = """{"alg":"EdDSA","typ":"qal-ent+jwt","kid":"ent-2026-11"}""")
        assertEquals(TokenRejection.BAD_SIGNATURE, rejected(verify(forged)))
    }

    @Test fun anEmptyPinnedSetFailsClosed() {
        val v = EntitlementTokenVerifier(PinnedKeys.NONE)
        assertEquals(TokenRejection.UNKNOWN_KEY, rejected(v.verify(valid(), SUB, DEVICE, now)))
        assertTrue(PinnedKeys.fromJwks("""{"keys":[]}""").byKid.isEmpty())
        assertTrue(PinnedKeys.fromJwks("{}").byKid.isEmpty())
    }

    @Test fun jwksParsingPinsOnlyEd25519Keys() {
        val pinned = PinnedKeys.fromJwks(
            """{"keys":[{"kty":"OKP","crv":"Ed25519","x":"${b64(key.raw)}","kid":"a"},
               {"kty":"EC","crv":"P-256","x":"AAAA","kid":"b"},
               {"kty":"OKP","crv":"Ed25519","x":"AAAA","kid":"short"}]}""",
        )
        assertEquals(setOf("a"), pinned.byKid.keys)
        assertTrue(EntitlementTokenVerifier(PinnedKeys.fromJwks(key.jwks())).verify(valid(), SUB, DEVICE, now) is TokenVerdict.Valid)
    }

    @Test fun onlyEdDsaWithTheExplicitTypeIsAccepted() {
        fun h(alg: String, typ: String = "qal-ent+jwt", extra: String = "") =
            TestTokens.mint(key, TestTokens.claims(iat), header = """{"alg":"$alg","typ":"$typ","kid":"${key.kid}"$extra}""")
        assertEquals(TokenRejection.ALGORITHM, rejected(verify(h("none"))))
        assertEquals(TokenRejection.ALGORITHM, rejected(verify(h("HS256"))))
        assertEquals(TokenRejection.ALGORITHM, rejected(verify(h("ES256"))))
        assertEquals(TokenRejection.ALGORITHM, rejected(verify(h("Ed25519"))))
        assertEquals(TokenRejection.TYPE, rejected(verify(h("EdDSA", typ = "JWT"))))
        for (f in listOf("crit", "jwk", "jku", "x5u", "x5c")) {
            assertEquals(f, TokenRejection.FORBIDDEN_HEADER, rejected(verify(h("EdDSA", extra = ""","$f":"x""""))))
        }
        // alg none with an empty signature part.
        val none = b64("""{"alg":"none","typ":"qal-ent+jwt","kid":"ent-2026-11"}""".toByteArray()) + "." + b64(TestTokens.claims(iat).toByteArray()) + "."
        assertEquals(TokenRejection.MALFORMED, rejected(verify(none)))
    }

    @Test fun malformedAndOversizedInputIsRejected() {
        assertEquals(TokenRejection.MALFORMED, rejected(verify("")))
        assertEquals(TokenRejection.MALFORMED, rejected(verify("a.b")))
        assertEquals(TokenRejection.MALFORMED, rejected(verify("a.b.c.d")))
        assertEquals(TokenRejection.MALFORMED, rejected(verify(valid().replace('.', '+'))))
        assertEquals(TokenRejection.MALFORMED, rejected(verify(valid() + "=")))
        assertEquals(TokenRejection.TOO_LARGE, rejected(verify("a".repeat(8 * 1024 + 1))))
    }

    @Test fun claimRulesAreEnforced() {
        fun t(c: String) = TestTokens.mint(key, c)
        assertEquals(TokenRejection.CLAIMS, rejected(verify(t(TestTokens.claims(iat, iss = "evil")))))
        assertEquals(TokenRejection.CLAIMS, rejected(verify(t(TestTokens.claims(iat, aud = "\"other\"")))))
        assertTrue(verify(t(TestTokens.claims(iat, aud = "[\"x\",\"qal-keyboard\"]"))) is TokenVerdict.Valid)
        assertEquals(TokenRejection.CLAIMS, rejected(verify(t(TestTokens.claims(iat, ver = "2")))))
        assertEquals(TokenRejection.CLAIMS, rejected(verify(t(TestTokens.claims(iat, features = """{"ON_DEVICE_AI_MODELS":"true"}""")))))
        assertEquals(TokenRejection.CLAIMS, rejected(verify(t(TestTokens.claims(iat, features = "[]")))))
        assertEquals(TokenRejection.CLAIMS, rejected(verify(t(TestTokens.claims(iat, sub = null)))))
        // validity longer than 24 h, grace longer than 72 h, exp before iat, grace before exp
        assertEquals(TokenRejection.TIME_WINDOW, rejected(verify(t(TestTokens.claims(iat, exp = iat + 24 * 3600 + 1)))))
        assertEquals(TokenRejection.TIME_WINDOW, rejected(verify(t(TestTokens.claims(iat, grace = iat + 24 * 3600 + 72 * 3600 + 1)))))
        assertEquals(TokenRejection.TIME_WINDOW, rejected(verify(t(TestTokens.claims(iat, exp = iat - 1, grace = iat + 10)))))
        assertEquals(TokenRejection.TIME_WINDOW, rejected(verify(t(TestTokens.claims(iat, grace = iat + 24 * 3600 - 1)))))
        // issued in the future beyond the 5 minute skew
        val future = now / 1000 + 600
        assertEquals(TokenRejection.TIME_WINDOW, rejected(verify(t(TestTokens.claims(future)))))
        assertTrue(verify(t(TestTokens.claims(now / 1000 + 60))) is TokenVerdict.Valid)
    }

    // ------------------------------------------------------------ gates (ADR 0013 D4.5)

    private fun token(): VerifiedToken = (verify(valid()) as TokenVerdict.Valid).token

    @Test fun gatesApplyBeforeExpiryOnlineAndOffline() {
        val t = token()
        val during = t.issuedAtMs + 3600_000
        for (online in listOf(true, false)) {
            val g = OfflineGates.evaluate(t, during, online)
            assertEquals(GateState.VALID, g.state)
            assertTrue(g.onDeviceModels)
        }
    }

    @Test fun inTheGracePeriodGatesApplyOnlyOffline() {
        val t = token()
        val expired = t.expiresAtMs + 1
        assertTrue(OfflineGates.evaluate(t, expired, online = false).onDeviceModels)
        assertEquals(GateState.OFFLINE_GRACE, OfflineGates.evaluate(t, expired, online = false).state)
        assertFalse(OfflineGates.evaluate(t, expired, online = true).onDeviceModels)
        assertTrue(OfflineGates.evaluate(t, t.offlineGraceUntilMs - 1, online = false).onDeviceModels)
    }

    @Test fun pastTheGraceOrWithoutATokenEverythingIsClosed() {
        val t = token()
        assertEquals(GateState.VOID, OfflineGates.evaluate(t, t.offlineGraceUntilMs, online = false).state)
        assertFalse(OfflineGates.evaluate(t, t.offlineGraceUntilMs + 1000, online = false).onDeviceModels)
        assertFalse(OfflineGates.evaluate(null, 0, online = false).onDeviceModels)
    }

    @Test fun aFeatureThatIsFalseOrMissingStaysClosed() {
        val off = (verify(TestTokens.mint(key, TestTokens.claims(iat, features = """{"ON_DEVICE_AI_MODELS":false}"""))) as TokenVerdict.Valid).token
        val none = (verify(TestTokens.mint(key, TestTokens.claims(iat, features = "{}"))) as TokenVerdict.Valid).token
        assertFalse(OfflineGates.evaluate(off, off.issuedAtMs + 1, online = false).onDeviceModels)
        assertFalse(OfflineGates.evaluate(none, none.issuedAtMs + 1, online = false).onDeviceModels)
    }

    @Test fun turningTheClockBackDoesNotExtendAToken() {
        val t = token()
        // The newest time seen (a later server Date or token iat) is the floor of "now".
        val floor = t.offlineGraceUntilMs + 5000
        val deviceClockSetBack = t.issuedAtMs
        assertFalse(OfflineGates.evaluate(t, maxOf(deviceClockSetBack, floor), online = false).onDeviceModels)
    }
}
