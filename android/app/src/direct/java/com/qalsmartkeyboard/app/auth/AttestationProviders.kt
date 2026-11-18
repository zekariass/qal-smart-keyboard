package com.qalsmartkeyboard.app.auth

import android.content.Context
import com.qalsmartkeyboard.app.BuildConfig

/**
 * Flavour `direct` (sideloaded, no Play Store): Play Integrity is not available, so guest sessions
 * are not offered unless a debug build asks for the mock. Typing, phone and Google sign-in work.
 */
object AttestationProviders {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): AttestationProvider =
        if (BuildConfig.MOCK_ATTESTATION) MockAttestationProvider else UnavailableAttestationProvider
}
