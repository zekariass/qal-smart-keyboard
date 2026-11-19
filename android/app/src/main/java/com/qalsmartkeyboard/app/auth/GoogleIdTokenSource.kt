package com.qalsmartkeyboard.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption

sealed interface GoogleResult {
    class Token(val idToken: String, val displayName: String?) : GoogleResult {
        override fun toString() = "Token(redacted)"
    }

    /** The user closed the account chooser: nothing to show. */
    data object Cancelled : GoogleResult

    /** No Google account on the device, or no client id configured. */
    data object Unavailable : GoogleResult

    data object Failed : GoogleResult
}

/** Gets a Google ID token whose `nonce` claim equals [nonce] (the server compares it, B2.2). */
interface GoogleIdTokenSource {
    /** False when no web client id is configured: the UI hides the button. */
    val isConfigured: Boolean

    suspend fun getIdToken(nonce: String): GoogleResult
}

/**
 * Credential Manager + Google ID library. [context] must be an Activity (the account chooser is a
 * bottom sheet). The client id is the OAuth *web* client id the backend accepts as audience.
 * Needs a device; not exercised by JVM tests.
 */
class CredentialManagerGoogleSource(
    private val context: Context,
    private val webClientId: String,
) : GoogleIdTokenSource {
    override val isConfigured: Boolean get() = webClientId.isNotBlank()

    override suspend fun getIdToken(nonce: String): GoogleResult {
        if (!isConfigured) return GoogleResult.Unavailable
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false) // let the user pick any account, including a new one
            .setNonce(nonce)
            .build()
        val quick = request(GetCredentialRequest.Builder().addCredentialOption(option).build())
        if (quick != GoogleResult.Unavailable) return quick
        // The bottom sheet found no usable account (NoCredentialException): Google's documented fallback is the explicit
        // "Sign in with Google" flow, which always lets the user pick or add an account (seen on a Samsung with several
        // Google accounts, 2026-11-19).
        val explicit = GetSignInWithGoogleOption.Builder(webClientId).setNonce(nonce).build()
        return request(GetCredentialRequest.Builder().addCredentialOption(explicit).build())
    }

    private suspend fun request(request: GetCredentialRequest): GoogleResult = try {
        val credential = CredentialManager.create(context).getCredential(context, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            GoogleResult.Token(google.idToken, google.displayName)
        } else {
            GoogleResult.Failed
        }
    } catch (_: GetCredentialCancellationException) {
        GoogleResult.Cancelled
    } catch (_: NoCredentialException) {
        GoogleResult.Unavailable
    } catch (e: GetCredentialException) {
        // Type only, never the message (it can name the account): enough to tell a console misconfiguration from a
        // provider problem when debugging on a device.
        Log.w(TAG, "Google sign-in failed: ${e.type}")
        GoogleResult.Failed
    } catch (_: GoogleIdTokenParsingException) {
        GoogleResult.Failed
    }

    private companion object {
        const val TAG = "QalGoogleSignIn"
    }
}
