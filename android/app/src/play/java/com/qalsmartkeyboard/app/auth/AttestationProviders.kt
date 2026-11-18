package com.qalsmartkeyboard.app.auth

import android.content.Context
import com.qalsmartkeyboard.app.BuildConfig

/** Flavour `play`: the Integrity API, or the mock in debug builds (`qal.mockAttestation`, default on for debug). */
object AttestationProviders {
    fun create(context: Context): AttestationProvider = when {
        BuildConfig.MOCK_ATTESTATION -> MockAttestationProvider
        BuildConfig.PLAY_INTEGRITY_PROJECT_NUMBER > 0L ->
            PlayIntegrityAttestationProvider(context.applicationContext, BuildConfig.PLAY_INTEGRITY_PROJECT_NUMBER)
        else -> UnavailableAttestationProvider // no Cloud project number configured
    }
}
