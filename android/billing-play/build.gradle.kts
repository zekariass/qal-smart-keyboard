plugins {
    alias(libs.plugins.android.library)
}

// :billing-play (ADR 0047): global store billing for the `play` flavour only. The RevenueCat SDK lives here so the
// `direct` APK never contains it; :app's play source set adapts RevenueCatStore to its StoreBilling port.
android {
    namespace = "com.qalsmartkeyboard.billing.play"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.revenuecat.purchases)
    testImplementation(libs.junit)
}
