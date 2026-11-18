package com.qalsmartkeyboard.sync

import com.qalsmartkeyboard.api.ApiError
import com.qalsmartkeyboard.data.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

enum class SyncStep { PREFERENCES, TYPING_LANGUAGES, DESTINATION_PROFILES, APP_RULES, DICTIONARY }

/** Outcome of one step. [Failed.retryable]: transient (network, timeout, 429, 5xx), worth another try soon. */
sealed interface StepResult {
    data object Synced : StepResult
    data object Unchanged : StepResult

    /** Not attempted on purpose, for example the dictionary switch is off or the account is a guest. */
    data class Skipped(val reason: String) : StepResult

    /** Partly done and to be retried: phonetic languages without a resolvable profile code (D21). */
    data class Deferred(val reason: String) : StepResult

    data class Failed(val code: String?, val status: Int?, val retryable: Boolean) : StepResult
}

enum class RunStatus { DONE, SIGNED_OUT, BUSY }

class SyncReport(val status: RunStatus, val steps: Map<SyncStep, StepResult> = emptyMap()) {
    /** True when WorkManager should schedule a retry with backoff. */
    val retryable: Boolean
        get() = steps.values.any { it is StepResult.Failed && it.retryable || it is StepResult.Deferred }
}

/** What differs per call: whether the account is a guest (the dictionary is refused for guests). */
data class SyncRequest(val isGuest: Boolean = false)

/**
 * One sync run (plan A2.4): preferences, typing languages, destination profiles, app rules and, when
 * the user switched it on, the personal dictionary, in that order. Steps are independent: a failure
 * in one does not stop the others (except a signed-out session, which ends the run).
 *
 * A run is exclusive per engine (one engine per process): a second concurrent call returns [RunStatus.BUSY]. Logs
 * carry step names and error codes only: no words, labels, package names or tokens.
 */
class SyncEngine(
    private val settings: SettingsRepository,
    private val http: SyncHttp,
    val state: SyncStateStore,
    val destinations: DestinationStore,
    private val dictionary: LocalDictionary?,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = PreferencesSync.create(settings, http, state)
    private val typing = TypingLanguagesSync(settings, DocSync(http, state, "/me/typing-languages", "typing"), ProfileDirectory(http, state, now))
    private val destination = DestinationSync.create(http, destinations, state)
    val consent = DictionaryConsent(http, state)
    private val running = AtomicBoolean(false)

    suspend fun runOnce(request: SyncRequest = SyncRequest()): SyncReport {
        if (!http.hasSession()) return SyncReport(RunStatus.SIGNED_OUT)
        if (!running.compareAndSet(false, true)) return SyncReport(RunStatus.BUSY)
        try {
            val out = LinkedHashMap<SyncStep, StepResult>()
            try {
                out[SyncStep.PREFERENCES] = step(SyncStep.PREFERENCES) { prefs.run().asStep() }
                out[SyncStep.TYPING_LANGUAGES] = step(SyncStep.TYPING_LANGUAGES) {
                    val r = typing.run()
                    if (r.deferred.isNotEmpty()) StepResult.Deferred("PROFILE_UNRESOLVED") else if (r.unchanged) StepResult.Unchanged else StepResult.Synced
                }
                out[SyncStep.DESTINATION_PROFILES] = step(SyncStep.DESTINATION_PROFILES) {
                    val r = destination.syncProfiles()
                    if (r.failed > 0) StepResult.Deferred("ITEM_REJECTED") else if (r.pushed + r.deleted == 0 && !r.changedLocally) StepResult.Unchanged else StepResult.Synced
                }
                out[SyncStep.APP_RULES] = step(SyncStep.APP_RULES) { destination.syncRules().asStep() }
                out[SyncStep.DICTIONARY] = when {
                    dictionary == null -> StepResult.Skipped("NO_DICTIONARY")
                    request.isGuest -> StepResult.Skipped("GUEST")
                    !consent.enabled -> StepResult.Skipped("CONSENT_OFF")
                    else -> step(SyncStep.DICTIONARY) { dictionaryStep(dictionary) }
                }
            } catch (e: ApiError.SignedOut) {
                return SyncReport(RunStatus.SIGNED_OUT, out)
            }
            return SyncReport(RunStatus.DONE, out)
        } finally {
            running.set(false)
        }
    }

    private suspend fun dictionaryStep(dictionary: LocalDictionary): StepResult {
        val r = DictionarySync(http, state, dictionary).run()
        return when (r.status) {
            DictionaryStatus.SYNCED -> StepResult.Synced
            DictionaryStatus.UNCHANGED -> StepResult.Unchanged
            DictionaryStatus.GUEST_NOT_ALLOWED -> StepResult.Skipped("GUEST")
            DictionaryStatus.CONSENT_REQUIRED -> {
                // The server says no consent: the local switch is out of date (withdrawn on another device).
                consent.remember(false)
                StepResult.Skipped("CONSENT_REQUIRED")
            }
            DictionaryStatus.NOT_ALLOWED -> StepResult.Skipped("NOT_ALLOWED")
        }
    }

    private suspend fun step(step: SyncStep, block: suspend () -> StepResult): StepResult = try {
        block().also { log("sync $step: ${it.javaClass.simpleName}") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiError.SignedOut) {
        throw e
    } catch (e: RevisionConflictExhausted) {
        log("sync $step: conflict not resolved")
        StepResult.Failed("REVISION_CONFLICT", 409, retryable = true)
    } catch (e: ApiError) {
        log("sync $step failed: ${e.code ?: e.javaClass.simpleName} ${e.status ?: ""}")
        StepResult.Failed(e.code, e.status, retryable = transient(e))
    }

    private fun DocResult.asStep() = if (this == DocResult.UNCHANGED) StepResult.Unchanged else StepResult.Synced

    private fun transient(e: ApiError): Boolean = when (e) {
        is ApiError.Network, is ApiError.Timeout, is ApiError.RateLimited -> true
        is ApiError.UpdateRequired -> false
        else -> (e.status ?: 0) >= 500
    }

    /**
     * Call when a sign-in finished (including a guest becoming an account). The server may have merged
     * the guest's documents and replaced its rule key with the account's (B3.4), so the key and the
     * rule document's merge base are dropped, and rules that exist only as hashes (made with the old key)
     * are forgotten; rules with a known package name are hashed again with the key read next.
     */
    fun signedIn() {
        state.put("ruleKey", null)
        state.put("rules", null)
        destinations.editRules { list -> list.filter { it.packageName != null } }
    }

    /** Call on sign-out or when another account signs in: forgets revisions, cursors, the rule key and the local profiles and rules. */
    fun accountChanged() {
        state.reset()
        destinations.clear()
    }

}
