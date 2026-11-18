package com.qalsmartkeyboard.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Survives rotation; the logic is in [SignInModel]. */
class SignInViewModel(val repository: AuthRepository) : ViewModel() {
    private val model = SignInModel(repository)
    val state: StateFlow<SignInState> = model.state

    fun setPhone(text: String) = model.setPhone(text)
    fun setCode(text: String) = model.setCode(text)
    fun setAgeConfirmed(on: Boolean) = model.setAgeConfirmed(on)
    fun dismissMessage() = model.dismissMessage()
    fun backToOptions() = model.backToOptions()
    fun reset() = model.reset()
    fun submitPhone() { viewModelScope.launch { model.submitPhone() } }
    fun submitCode() { viewModelScope.launch { model.submitCode() } }
    fun resend() { viewModelScope.launch { model.resend() } }
    fun google(source: GoogleIdTokenSource) { viewModelScope.launch { model.signInWithGoogle(source) } }
    fun guest() { viewModelScope.launch { model.continueAsGuest() } }
    fun signOut() { viewModelScope.launch { repository.signOut() } }
}
