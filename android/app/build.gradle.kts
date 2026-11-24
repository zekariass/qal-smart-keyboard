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
//   qal.entitlementJwksFile        JWK Set (the format of /.well-known/jwks.json) of the entitlement-token public keys pinned in this build
//                                  (ADR 0013). Without it the set is empty: tokens never verify and no offline gate opens (fail closed).
//   qal.mockAttestation            true/false, debug builds only: use MockAttestationProvider (default true for debug)
//   qal.sentryDsn                  Sentry DSN for content-free crash reports (ADR 0024); also env QAL_SENTRY_DSN_ANDROID.
//                                  Blank (the default: local, CI, tests): Sentry is never started and nothing is sent.
//   qal.revenuecatAndroidKey       RevenueCat **public** Android SDK key (`goog_...`, ADR 0047), play flavour only. Blank (the
//                                  default): store billing is hidden. A secret key (`sk_...`) fails the build.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun deployProp(name: String): String? = providers.gradleProperty(name).orNull ?: localProps.getProperty(name)
// The pinned entitlement keys (ADR 0013 D4.2): compiled in, never fetched. A path is resolved against android/.
val entitlementJwks: String = deployProp("qal.entitlementJwksFile")?.let { rootProject.file(it).readText() } ?: "{\"keys\":[]}"
// A DSN is not a secret (it only allows sending events), but it differs per environment and must stay out of git.
val sentryDsn: String = (deployProp("qal.sentryDsn") ?: providers.environmentVariable("QAL_SENTRY_DSN_ANDROID").orNull).orEmpty().trim()
// ADR 0047: only a public SDK key may ship in the app; a secret API key would let anyone act on the RevenueCat project.
val revenuecatKey: String = deployProp("qal.revenuecatAndroidKey").orEmpty().trim().also { key ->
    if (key.startsWith("sk_") || key.startsWith("atk_")) {
        throw GradleException("qal.revenuecatAndroidKey must be the PUBLIC Android SDK key (goog_...), never a secret key")
    }
}
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
        buildConfigField("String", "ENTITLEMENT_JWKS", quoted(entitlementJwks.replace(Regex("\\s+"), " ")))
        buildConfigField("String", "SENTRY_DSN", quoted(sentryDsn))
        buildConfigField("long", "PLAY_INTEGRITY_PROJECT_NUMBER", (deployProp("qal.playIntegrityProjectNumber")?.toLongOrNull() ?: 0L).toString() + "L")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "REVENUECAT_ANDROID_KEY", quoted(revenuecatKey))
        }
        create("direct") {
            dimension = "distribution"
            // No store SDK in the direct APK (the :billing-play module is play-only).
            buildConfigField("String", "REVENUECAT_ANDROID_KEY", quoted(""))
        }
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
    implementation(project(":ai-client"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.sentry.android.core) // ADR 0024; started only with a DSN and the user's consent switch on
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.id)
    "playImplementation"(libs.play.integrity)
    "playImplementation"(project(":billing-play"))
    "directImplementation"(project(":billing-direct"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.browser)
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