plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qalsmartkeyboard.ime"
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

// JVM unit tests that drive the real core (end-to-end typing over the bundled packs) load the
// host build of the Rust library through JNA, like :core-bindings' own unit test.
tasks.withType<Test>().configureEach {
    dependsOn(":core-bindings:cargoHostBuild")
    systemProperty("jna.library.path", rootProject.layout.projectDirectory.dir("../core/target/release").asFile.absolutePath)
}

dependencies {
    implementation(project(":core-bindings"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.jna.jvm)
}
