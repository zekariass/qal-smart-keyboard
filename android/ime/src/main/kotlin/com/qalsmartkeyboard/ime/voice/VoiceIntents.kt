package com.qalsmartkeyboard.ime.voice

import android.content.Intent

/**
 * The intent the keyboard sends to the companion app's microphone-permission screen (`VoicePermissionActivity` in
 * `:app`, which declares [ACTION_VOICE_PERMISSION]). An input method cannot show the runtime-permission dialog, so the
 * app asks; RECORD_AUDIO is granted to the app package, which the keyboard service belongs to. The intent is limited
 * to our own package and starts a new task (it is sent from a service).
 */
object VoiceIntents {
    const val ACTION_VOICE_PERMISSION = "com.qalsmartkeyboard.action.VOICE_PERMISSION"

    fun permission(packageName: String): Intent =
        Intent(ACTION_VOICE_PERMISSION)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
