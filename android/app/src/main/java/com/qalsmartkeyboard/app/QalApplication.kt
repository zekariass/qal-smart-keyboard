package com.qalsmartkeyboard.app

import android.app.Application
import com.qalsmartkeyboard.app.ai.AiGraph
import com.qalsmartkeyboard.ime.ai.AiActionsHolder
import com.qalsmartkeyboard.ime.theme.ThemeAccessHolder

/**
 * Registers what the keyboard service cannot know itself (A2.5): the system creates
 * `KeyboardImeService`, and `:ime` has no network code, so the AI implementation is handed over
 * here as a factory. Nothing is built now (Keystore and file I/O); the service calls the factory on
 * its own background thread.
 */
class QalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val context = applicationContext
        AiActionsHolder.factory = { AiGraph.get(context).actions }
        ThemeAccessHolder.factory = { AiGraph.get(context).themeAccess }
    }
}
