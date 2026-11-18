package com.qalsmartkeyboard.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

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
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
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
        } catch (_: GetCredentialException) {
            GoogleResult.Failed
        } catch (_: GoogleIdTokenParsingException) {
            GoogleResult.Failed
        }
    }
}
