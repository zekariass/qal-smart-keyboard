plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qalsmartkeyboard.data"
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
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okio)
    testImplementation(libs.androidx.datastore.core.okio)
}
