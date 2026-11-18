plugins {
    alias(libs.plugins.android.library)
}

// :ai-client (A2.5): the network side of the keyboard's AI toolbar. It implements the `AiActions`
// port that :ime defines (:ime itself stays network-free, see NoNetworkTest) over :api's OkHttp
// stack, verifies the entitlement token on device (ADR 0013) and reads the destination profiles
// from :sync's local store. :app wires it into the IME through `AiActionsHolder`.
android {
    namespace = "com.qalsmartkeyboard.aiclient"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = providers.gradleProperty("qal.minSdk").get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":ime"))
    implementation(project(":data"))
    implementation(project(":api"))
    implementation(project(":sync"))
    // Ed25519 verification on every API level (the JDK's Ed25519 Signature exists from Android 13 only).
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
