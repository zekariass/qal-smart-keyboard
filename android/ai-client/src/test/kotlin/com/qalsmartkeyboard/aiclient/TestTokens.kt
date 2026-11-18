package com.qalsmartkeyboard.aiclient

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.util.Base64

/**
 * Mints entitlement tokens the way the server does (ADR 0013 D2), with the **JDK's** Ed25519
 * signer, independent of the BouncyCastle verifier under test.
 */
class TestKey(val kid: String) {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val private: PrivateKey = pair.private

    /** Raw 32-byte public key (RFC 8032 little-endian y with the x sign bit). */
    val raw: ByteArray = run {
        val p = (pair.public as EdECPublicKey).point
        val y = p.y.toByteArray().reversedArray().copyOf(32)
        if (p.isXOdd) y[31] = (y[31].toInt() or 0x80).toByte()
        y
    }

    fun pinned() = PinnedKeys(mapOf(kid to raw))

    fun jwks() = """{"keys":[{"kty":"OKP","crv":"Ed25519","x":"${b64(raw)}","kid":"$kid","use":"sig","alg":"EdDSA"}]}"""
}

fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

object TestTokens {
    const val SUB = "0193a5c2-6f1e-7c3a-9b2d-4e5f6a7b8c9d"
    const val DEVICE = "device-1"

    fun claims(
        iat: Long,
        exp: Long = iat + 24 * 3600,
        grace: Long = exp + 72 * 3600,
        sub: String? = SUB,
        device: String? = DEVICE,
        features: String = """{"ON_DEVICE_AI_MODELS":true}""",
        iss: String = "qal-smart-keyboard",
        aud: String = "\"qal-keyboard\"",
        ver: String = "1",
        plan: String = "PREMIUM",
    ) = buildString {
        append("""{"iss":"$iss","aud":$aud,"ver":$ver,"planCode":"$plan","features":$features,"policyVersion":"PROVISIONAL_V2",""")
        if (sub != null) append(""""sub":"$sub",""")
        if (device != null) append(""""deviceId":"$device",""")
        append(""""iat":$iat,"exp":$exp,"offlineGraceUntil":$grace,"jti":"2f9c0d4e-0000-4000-8000-000000000001"}""")
    }

    fun mint(
        key: TestKey,
        claimsJson: String,
        header: String = """{"alg":"EdDSA","typ":"qal-ent+jwt","kid":"${key.kid}"}""",
    ): String {
        val signed = b64(header.toByteArray()) + "." + b64(claimsJson.toByteArray())
        val sig = Signature.getInstance("Ed25519").apply { initSign(key.private); update(signed.toByteArray(Charsets.US_ASCII)) }.sign()
        return signed + "." + b64(sig)
    }
}
