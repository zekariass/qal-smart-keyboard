package com.qalsmartkeyboard.app.auth

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Production attestation (A2.2): a standard Play Integrity token over the server nonce. The server
 * decodes and verifies it (B2.3: `PlayIntegrityVerifier`); the client only forwards the opaque
 * token. The nonce from `/auth/attestation-nonces` is base64url without padding, which is what the
 * API requires. Needs a device with Play Services, a Google Cloud project number linked to the app
 * in the Play Console, and a build installed from Play (or an internal test track); it cannot be
 * exercised on the JVM. Any failure maps to [AttestationResult.Failed]: details are not shown or logged.
 */
class PlayIntegrityAttestationProvider(
    private val context: Context,
    private val cloudProjectNumber: Long,
) : AttestationProvider {
    override val isSupported: Boolean = true

    override suspend fun attest(nonce: String): AttestationResult = suspendCancellableCoroutine { cont ->
        try {
            IntegrityManagerFactory.create(context)
                .requestIntegrityToken(
                    IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(cloudProjectNumber).build(),
                )
                .addOnSuccessListener { if (cont.isActive) cont.resume(AttestationResult.Token(it.token())) }
                .addOnFailureListener { if (cont.isActive) cont.resume(AttestationResult.Failed) }
        } catch (_: RuntimeException) {
            if (cont.isActive) cont.resume(AttestationResult.Failed)
        }
    }
}
