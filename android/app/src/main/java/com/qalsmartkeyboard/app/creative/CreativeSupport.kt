package com.qalsmartkeyboard.app.creative

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qalsmartkeyboard.aiclient.FeatureAccess
import com.qalsmartkeyboard.aiclient.ServerFeatureAccess
import com.qalsmartkeyboard.app.ai.AiGraph

/**
 * The activity's view model of type [T] (created by [create] on first use). The creative tools keep their drafts in view
 * models of the activity, so a draft survives rotation and a detour to the plans screen, which replaces the tool's
 * screen while the user subscribes.
 */
@Composable
inline fun <reified T : ViewModel> activityViewModel(noinline create: CreationExtras.() -> T): T {
    val context = LocalContext.current
    return remember(context) {
        val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>().first()
        ViewModelProvider(owner, viewModelFactory { initializer<T> { create() } })[T::class.java]
    }
}

/**
 * Whether this device may use the premium parts of the card maker right now (`CARD_MAKER`), from the same entitlement
 * port family as the premium keyboard themes ([AiGraph.cardMakerAccess] over the verified token and the plan hints).
 */
@Composable
fun rememberCardMakerAccess(): State<FeatureAccess> = rememberFeatureAccess("qal-card-access") { it.cardMakerAccess }

/** Whether this device may use the image annotator's advanced tools right now (`IMAGE_ANNOTATOR`, [AiGraph.annotatorAccess]). */
@Composable
fun rememberAnnotatorAccess(): State<FeatureAccess> = rememberFeatureAccess("qal-annotator-access") { it.annotatorAccess }

/**
 * The answer of one boolean feature gate ([port] of the AI graph), kept current. The port may open Keystore-backed files
 * the first time, so it is built off the main thread; until then the answer is "check needed". Basic tools never wait
 * for it.
 */
@Composable
private fun rememberFeatureAccess(threadName: String, port: (AiGraph) -> ServerFeatureAccess): State<FeatureAccess> {
    val state = remember { mutableStateOf(FeatureAccess.NEEDS_CHECK) }
    val context: Context = LocalContext.current
    DisposableEffect(context) {
        var handle: AutoCloseable? = null
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread({
            val graph = try { AiGraph.get(context) } catch (_: Exception) { null }
            if (graph == null) {
                if (!closed.get()) state.value = FeatureAccess.NOT_ON_PLAN
            } else if (!closed.get()) {
                val gate = port(graph)
                state.value = gate.access
                handle = gate.addListener { state.value = gate.access }
                // Blocking and throttled: refreshes the token and the plan hints so a plan bought elsewhere shows up.
                try { graph.entitlements.refresh() } catch (_: Exception) { }
                if (!closed.get()) state.value = gate.access
            }
        }, threadName)
        thread.start()
        onDispose {
            closed.set(true)
            handle?.close()
        }
    }
    return state
}
