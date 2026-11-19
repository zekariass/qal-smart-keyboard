package com.qalsmartkeyboard.app.email

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.qalsmartkeyboard.app.ai.AiGraph
import com.qalsmartkeyboard.ime.ai.AiEmailHandoff
import com.qalsmartkeyboard.ime.ai.AiLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The e-mail writer as a screen of [com.qalsmartkeyboard.app.MainActivity] (ADR 0031): the shared AI client (built off
 * the main thread), the remembered sender name, and the keyboard's inputs when it was opened with "Open full editor"
 * (an in-memory handoff, taken once).
 */
@Composable
fun EmailWriterRoute(model: EmailWriterModel, senderName: String, onLink: (AiLink) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(model) {
        // The AI client opens Keystore-backed files: built on a background thread, then handed to the model.
        val actions = withContext(Dispatchers.IO) { AiGraph.get(context).actions }
        model.attach(actions)
        model.start(senderName, AiEmailHandoff.take())
    }
    val host = remember { EmailWriterHost.of(context, onLink) }
    EmailWriterScreen(model, host, onBack, modifier)
}
