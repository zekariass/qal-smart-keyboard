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

// The token tests verify real Ed25519 signatures through the Rust core (host build via JNA), like :ime's tests.
tasks.withType<Test>().configureEach {
    dependsOn(":core-bindings:cargoHostBuild")
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("../core/target/release").asFile.absolutePath)
}

dependencies {
    implementation(project(":ime"))
    implementation(project(":data"))
    implementation(project(":api"))
    implementation(project(":sync"))
    // Ed25519 verification of the entitlement token goes through keyboard-core's `verifyEd25519` (ADR 0013 D4.3).
    implementation(project(":core-bindings"))
    testImplementation(libs.junit)
    testImplementation(libs.jna.jvm)
    testImplementation(libs.okhttp.mockwebserver)
}
