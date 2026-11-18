package com.qalsmartkeyboard.app.auth

sealed interface AttestationResult {
    /** An attestation token bound to the server nonce (the provider is always PLAY_INTEGRITY on Android). */
    class Token(val token: String) : AttestationResult {
        override fun toString() = "Token(redacted)"
    }

    /** This build/device cannot attest (no Play Store, no Cloud project number configured, direct flavour). */
    data object Unavailable : AttestationResult

    /** The platform refused or failed (Play Services missing, network, throttled). Details are never shown or logged. */
    data object Failed : AttestationResult
}

/**
 * Produces the device-attestation evidence the server verifies before it creates a guest
 * session (B2.3). The nonce is the single-use server nonce from `/auth/attestation-nonces`
 * (43 base64url characters, which is also what the Play Integrity API requires of a nonce).
 *
 * Implementations: [MockAttestationProvider] (dev/debug, accepted only by a backend running the
 * mock verifier), `PlayIntegrityAttestationProvider` (flavour `play`, calls the Integrity API),
 * [UnavailableAttestationProvider] (flavour `direct`, no Play Store).
 */
interface AttestationProvider {
    /** False when guest sessions cannot be offered at all (the UI hides "Continue as guest"). */
    val isSupported: Boolean

    suspend fun attest(nonce: String): AttestationResult
}

/**
 * Dev/debug only: the token format the backend's `MockPlayIntegrityVerifier` accepts
 * (`mock-play-integrity.v1.<nonce>.valid`). Production backends run the real verifier and answer
 * 403 `GUEST_NOT_ALLOWED` to this token.
 */
object MockAttestationProvider : AttestationProvider {
    override val isSupported: Boolean = true
    override suspend fun attest(nonce: String): AttestationResult =
        AttestationResult.Token("mock-play-integrity.v1.$nonce.valid")
}

object UnavailableAttestationProvider : AttestationProvider {
    override val isSupported: Boolean = false
    override suspend fun attest(nonce: String): AttestationResult = AttestationResult.Unavailable
}
