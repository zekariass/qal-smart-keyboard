package com.qalsmartkeyboard.app.ai

import android.os.Build
import com.qalsmartkeyboard.api.TransportConfig

/**
 * Debug builds only: a build whose API base URL is the emulator's host alias `10.0.2.2` cannot reach the server from a
 * physical phone; every AI call then fails (it used to hang until "That took too long"). [check] logs one clear warning
 * with the fix, never user content. See `android/README.md` ("Building for a physical phone").
 */
object BaseUrlGuard {
    const val WARNING = "API base URL uses the emulator-only host 10.0.2.2 but this is not an emulator: the server is " +
        "unreachable. Build with -Pqal.apiBaseUrl=http://localhost:8080 and run adb reverse tcp:8080 tcp:<server port> " +
        "(see android/README.md)."

    /** Calls [warn] with [WARNING] when [baseUrl] is emulator-only and the device [isEmulator] is not; returns whether it warned. */
    fun check(baseUrl: String, isEmulator: Boolean, warn: (String) -> Unit): Boolean {
        if (isEmulator || !TransportConfig.isEmulatorOnly(baseUrl)) return false
        warn(WARNING)
        return true
    }

    /** The usual signs of the Android emulator (goldfish/ranchu hardware, generic or sdk_gphone builds). */
    fun isEmulator(
        fingerprint: String = Build.FINGERPRINT.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        hardware: String = Build.HARDWARE.orEmpty(),
        product: String = Build.PRODUCT.orEmpty(),
    ): Boolean =
        hardware == "goldfish" || hardware == "ranchu" ||
            fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
            model.contains("sdk_gphone") || model.contains("Emulator") || model.contains("Android SDK built for") ||
            product.startsWith("sdk") || product.contains("emulator")
}
