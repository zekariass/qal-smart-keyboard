import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Deployment-specific values (A2.2) come from `local.properties` (never committed) or from -P Gradle
// properties; nothing secret is needed: the Google client id is public, but it differs per environment.
//   qal.apiBaseUrl                 API base URL (debug default http://10.0.2.2:8080, the emulator's host loopback)
//   qal.googleWebClientId          OAuth *web* client id the backend lists as an accepted audience (blank: Google sign-in hidden)
//   qal.playIntegrityProjectNumber Google Cloud project number linked to the Play Integrity API (0: real attestation unavailable)
//   qal.mockAttestation            true/false, debug builds only: use MockAttestationProvider (default true for debug)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun deployProp(name: String): String? = providers.gradleProperty(name).orNull ?: localProps.getProperty(name)
fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.qalsmartkeyboard.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.qalsmartkeyboard.app"
        minSdk = providers.gradleProperty("qal.minSdk").get().toInt()
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", quoted(deployProp("qal.googleWebClientId").orEmpty()))
        buildConfigField("long", "PLAY_INTEGRITY_PROJECT_NUMBER", (deployProp("qal.playIntegrityProjectNumber")?.toLongOrNull() ?: 0L).toString() + "L")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("direct") { dimension = "distribution" }
    }

    // ABI splits: one APK per ABI (Play/CI) plus a universal APK for sideloading the `direct`
    // flavour. Keep this list in sync with qal.abis (gradle.properties).
    splits {
        abi {
            isEnable = true
            reset()
            include(*providers.gradleProperty("qal.abis").get().split(",").map { it.trim() }.toTypedArray())
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", quoted(deployProp("qal.apiBaseUrl") ?: "http://10.0.2.2:8080"))
            buildConfigField("boolean", "MOCK_ATTESTATION", (deployProp("qal.mockAttestation")?.toBoolean() ?: true).toString())
        }
        release {
            // https only (TransportConfig refuses cleartext outside the local stack). Placeholder until the production host exists.
            buildConfigField("String", "API_BASE_URL", quoted(deployProp("qal.apiBaseUrl") ?: "https://api.qalsmartkeyboard.example"))
            buildConfigField("boolean", "MOCK_ATTESTATION", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // D28: java.time (the generated API client's OffsetDateTime) below API 26.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric Compose test
    }
}

dependencies {
    implementation(project(":core-bindings"))
    implementation(project(":ime"))
    implementation(project(":data"))
    implementation(project(":api"))
    implementation(project(":packs-sync"))
    implementation(project(":sync"))
    implementation(libs.androidx.work.runtime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)
    "playImplementation"(libs.play.integrity)
    "playImplementation"(project(":billing-play"))
    "directImplementation"(project(":billing-direct"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okio)
    testImplementation(libs.androidx.datastore.core.okio)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}