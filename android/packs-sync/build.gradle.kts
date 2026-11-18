plugins {
    alias(libs.plugins.android.library)
}

// :packs-sync (A2.3): language-pack updates (check, download, verify, atomic swap). It is the only
// place with network code for packs: :ime stays network-free (NoNetworkTest) and only shares files
// with this module (see ime.packs.PackJournal). Plain functions over files and OkHttp; the
// WorkManager job that drives it lives in :app.
android {
    namespace = "com.qalsmartkeyboard.packsync"
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

// The tamper tests drive the real Rust core (host build via JNA), like :ime's pack manager tests.
tasks.withType<Test>().configureEach {
    dependsOn(":core-bindings:cargoHostBuild")
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("../core/target/release").asFile.absolutePath)
}

dependencies {
    implementation(project(":core-bindings"))
    implementation(project(":ime"))
    implementation(project(":api"))
    testImplementation(libs.junit)
    testImplementation(libs.jna.jvm)
    testImplementation(libs.okhttp.mockwebserver)
}
