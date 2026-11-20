package com.qalsmartkeyboard.app.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qalsmartkeyboard.api.SessionManager
import com.qalsmartkeyboard.api.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Survives rotation and runs the checkout on [viewModelScope]: polling goes on while the user leaves the plans screen
 * for another one. A process death loses this object; the next one finds the pending order on disk and resumes.
 */
class PlansViewModel(val model: PlansModel, session: SessionManager?) : ViewModel() {
    private var checkout: Job? = null

    init {
        load()
        if (session != null) {
            viewModelScope.launch {
                session.state.drop(1).distinctUntilChanged { a, b -> (a is SessionState.SignedIn) == (b is SessionState.SignedIn) }.collect { s ->
                    if (s is SessionState.SignedOut) {
                        checkout?.cancel()
                        model.signedOut()
                    } else {
                        load()
                    }
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch { model.load() }
    }

    fun selectOffer(key: String) = model.selectOffer(key)
    fun selectMethod(code: String) = model.selectMethod(code)
    fun dismissMessage() = model.dismissMessage()

    fun buy() = run { model.buy() }
    fun buyFromStore(host: PurchaseHost) = run { model.buyFromStore(host) }
    fun onReturned(result: String?) = run { model.onReturned(result) }
    fun onCheckoutClosed() = run { model.onCheckoutClosed() }
    fun reopenCheckout() = run { model.reopenCheckout() }
    fun refreshCheckout() = run { model.refreshCheckout() }

    fun closeCheckout() {
        checkout?.cancel()
        model.closeCheckout()
    }

    private fun run(block: suspend () -> Unit) {
        checkout?.cancel()
        checkout = viewModelScope.launch { block() }
    }
}
