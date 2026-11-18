plugins {
    alias(libs.plugins.android.library)
}

// :sync (A2.4): settings, destination-profile, app-rule and personal-dictionary sync with the backend.
// Network code lives here and in :packs-sync; :ime and :data stay network-free (NoNetworkTest). It has
// no dependency on :ime or the Rust core: the personal dictionary is reached through the small
// `LocalDictionary` port that :app implements over `UserDictionary`. The WorkManager job is in :app.
android {
    namespace = "com.qalsmartkeyboard.sync"
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
    implementation(project(":data"))
    implementation(project(":api"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okio)
    testImplementation(libs.androidx.datastore.core.okio)
}
