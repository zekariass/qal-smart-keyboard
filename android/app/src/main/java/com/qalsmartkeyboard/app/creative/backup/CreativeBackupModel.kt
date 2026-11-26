package com.qalsmartkeyboard.app.creative.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Who is using the studio, as far as the backup card cares. */
enum class BackupAccount { SIGNED_OUT, GUEST, REGISTERED }

/** Where the card is. */
enum class BackupPhase {
    /** The usage is being read. */
    CHECKING,

    /** Nobody is signed in: the card explains and offers "Sign in". */
    SIGNED_OUT,

    /** A guest account: the cloud backup needs a registered account (the server refuses guests). */
    GUEST,

    /** The server switched the backup off (404 on every call): "not available right now". */
    UNAVAILABLE,

    /** Signed in, consent not granted: the explanation and "Turn on cloud backup". */
    CONSENT_OFF,

    /** Consent granted: usage, "Back up now", "Restore from cloud", "Delete cloud copy", "Turn off". */
    CONSENT_ON,

    /** The usage could not be read ([BackupUiState.problem]): "Try again". */
    PROBLEM,
}

/** What is running now. */
sealed interface BackupWork {
    data class BackingUp(val done: Int, val total: Int) : BackupWork

    data class Restoring(val done: Int, val total: Int) : BackupWork

    data object Deleting : BackupWork

    /** Granting or withdrawing the consent. */
    data object Consent : BackupWork
}

/** What the last action did, shown until dismissed. */
sealed interface BackupResult {
    data class BackedUp(val report: RunReport) : BackupResult

    data class Restored(val report: RunReport) : BackupResult

    data object CloudDeleted : BackupResult

    data object TurnedOn : BackupResult

    /** The consent was withdrawn: the server deleted every cloud copy. */
    data object TurnedOff : BackupResult

    /** An action could not start or finish ([error]); nothing changed except what the message says. */
    data class Failed(val error: BackupError) : BackupResult

    /** Another action is already running. */
    data object Busy : BackupResult
}

data class BackupUiState(
    val phase: BackupPhase = BackupPhase.CHECKING,
    val summary: RemoteSummary? = null,
    val problem: BackupError? = null,
    val work: BackupWork? = null,
    val result: BackupResult? = null,
) {
    val working: Boolean get() = work != null
}

/** Grants or withdraws the `CREATIVE_BACKUP` consent through the app's consents mechanism. Throws [BackupException]. */
interface BackupConsentPort {
    suspend fun set(granted: Boolean)
}

/**
 * The logic of the Creative studio's "Cloud backup" card (T8.15 S3): which of its states it is in, the consent flow, and the
 * three actions, each started by a tap. Suspend functions over [BackupEngine] and [BackupRemote], so the tests run them
 * directly. The engine's blocking local work runs on [io].
 *
 * Turning the backup off withdraws the consent, which makes the server delete every cloud copy (ADR 0050 #3); only after that
 * answer does the device forget its record. Nothing here logs; drawings never appear in a message.
 */
class CreativeBackupModel(
    private val engine: BackupEngine,
    private val remote: BackupRemote,
    private val consent: BackupConsentPort,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Reads the account's state: usage and whether the consent is on. Call when the card appears and when the account changes. */
    suspend fun refresh(account: BackupAccount) {
        when (account) {
            BackupAccount.SIGNED_OUT -> _state.update { it.copy(phase = BackupPhase.SIGNED_OUT, summary = null, problem = null) }
            BackupAccount.GUEST -> _state.update { it.copy(phase = BackupPhase.GUEST, summary = null, problem = null) }
            BackupAccount.REGISTERED -> {
                if (_state.value.summary == null) _state.update { it.copy(phase = BackupPhase.CHECKING, problem = null) }
                try {
                    val s = remote.summary()
                    _state.update { it.copy(phase = if (s.consentGranted) BackupPhase.CONSENT_ON else BackupPhase.CONSENT_OFF, summary = s, problem = null) }
                } catch (e: BackupException) {
                    _state.update { accountLevel(it, e.error) ?: if (it.summary == null) it.copy(phase = BackupPhase.PROBLEM, problem = e.error) else it.copy(problem = e.error) }
                }
            }
        }
    }

    /** "Turn on cloud backup" after the person confirmed: records the consent. Nothing is uploaded by this. */
    suspend fun turnOn() {
        if (!begin(BackupWork.Consent)) return
        try {
            consent.set(true)
            val summary = readSummary()
            _state.update { it.copy(phase = BackupPhase.CONSENT_ON, summary = summary ?: it.summary, work = null, result = BackupResult.TurnedOn) }
        } catch (e: BackupException) {
            _state.update { (accountLevel(it, e.error) ?: it).copy(work = null, result = BackupResult.Failed(e.error)) }
        }
    }

    /** "Turn off" after the person confirmed: withdraws the consent (the server deletes every cloud copy), then forgets the record. */
    suspend fun turnOff() {
        if (!begin(BackupWork.Consent)) return
        try {
            consent.set(false)
            engine.forgetRecord()
            val summary = readSummary()
            _state.update { it.copy(phase = BackupPhase.CONSENT_OFF, summary = summary, work = null, result = BackupResult.TurnedOff) }
        } catch (e: BackupException) {
            _state.update { (accountLevel(it, e.error) ?: it).copy(work = null, result = BackupResult.Failed(e.error)) }
        }
    }

    /** "Back up now": uploads new and changed drawings (never changes the device's drawings). */
    suspend fun backUp() {
        if (_state.value.phase != BackupPhase.CONSENT_ON) return
        if (!begin(BackupWork.BackingUp(0, 0))) return
        finish(withContext(io) { engine.backUp { done, total -> _state.update { s -> if (s.work is BackupWork.BackingUp) s.copy(work = BackupWork.BackingUp(done, total)) else s } } }) {
            BackupResult.BackedUp(it)
        }
    }

    /** "Restore from cloud". The caller reads the gallery again afterwards (see [BackupUiState.result]). */
    suspend fun restore() {
        if (_state.value.phase != BackupPhase.CONSENT_ON) return
        if (!begin(BackupWork.Restoring(0, 0))) return
        finish(withContext(io) { engine.restore { done, total -> _state.update { s -> if (s.work is BackupWork.Restoring) s.copy(work = BackupWork.Restoring(done, total)) else s } } }) {
            BackupResult.Restored(it)
        }
    }

    /** "Delete cloud copy" after the person confirmed: every cloud item, whatever the consent. The device's drawings stay. */
    suspend fun deleteCloud() {
        if (_state.value.phase != BackupPhase.CONSENT_ON && _state.value.phase != BackupPhase.CONSENT_OFF) return
        if (!begin(BackupWork.Deleting)) return
        finish(withContext(io) { engine.clearCloud() }) { r -> r.stopped?.let { BackupResult.Failed(it) } ?: BackupResult.CloudDeleted }
    }

    fun dismissResult() = _state.update { it.copy(result = null) }

    private fun begin(work: BackupWork): Boolean {
        var started = false
        _state.update { s ->
            if (s.work != null || engine.running) {
                s.copy(result = BackupResult.Busy)
            } else {
                started = true
                s.copy(work = work, result = null)
            }
        }
        return started
    }

    /** Applies a run's outcome: the result message, the phase an account-level error implies, then the usage again. */
    private suspend fun finish(outcome: RunOutcome, success: (RunReport) -> BackupResult) {
        try {
            when (outcome) {
                RunOutcome.Busy -> _state.update { it.copy(work = null, result = BackupResult.Busy) }
                RunOutcome.NotSignedIn -> _state.update { it.copy(work = null, phase = BackupPhase.SIGNED_OUT, summary = null, result = null) }
                is RunOutcome.Finished -> {
                    val stopped = outcome.report.stopped
                    _state.update { s ->
                        val next = if (stopped != null) accountLevel(s, stopped) ?: s else s
                        next.copy(work = null, result = success(outcome.report))
                    }
                    if (_state.value.phase == BackupPhase.CONSENT_ON || _state.value.phase == BackupPhase.CONSENT_OFF) {
                        readSummary()?.let { s -> _state.update { it.copy(summary = s) } }
                    }
                }
            }
        } finally {
            _state.update { if (it.work != null) it.copy(work = null) else it }
        }
    }

    /** The usage, or null when it cannot be read right now (the card keeps what it shows). */
    private suspend fun readSummary(): RemoteSummary? = try {
        remote.summary()
    } catch (e: CancellationException) {
        throw e
    } catch (_: BackupException) {
        null
    }

    /** What an account-level error means for the card (the state it should show), or null for a per-item or transient error. */
    private fun accountLevel(s: BackupUiState, error: BackupError): BackupUiState? = when (error) {
        BackupError.FeatureOff -> s.copy(phase = BackupPhase.UNAVAILABLE, summary = null, problem = null)
        BackupError.SignedOut -> s.copy(phase = BackupPhase.SIGNED_OUT, summary = null, problem = null)
        BackupError.GuestNotAllowed -> s.copy(phase = BackupPhase.GUEST, summary = null, problem = null)
        BackupError.ConsentRequired -> s.copy(phase = BackupPhase.CONSENT_OFF, problem = null)
        else -> null
    }
}
