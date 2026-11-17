import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

// Builds the Rust core (../core) for the Android ABIs with cargo-ndk, generates the Kotlin
// bindings with uniffi-bindgen (library mode, from the compiled .so) and packages both into this
// module (SD 6.2 `:core-bindings`). Generated code and .so files are build outputs, never committed.
// Requires: rustup (toolchain pinned by core/rust-toolchain.toml), the three Android Rust targets,
// `cargo install cargo-ndk`, and the NDK (see android/README.md).

val coreDirectory = rootProject.layout.projectDirectory.dir("../core")
val abiList = providers.gradleProperty("qal.abis").get().split(",").map { it.trim() }
val minSdkLevel = providers.gradleProperty("qal.minSdk").get()
val bindgenAbi = abiList.first() // any ABI's library carries the same UniFFI metadata

android {
    namespace = "com.qalsmartkeyboard.core"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = minSdkLevel.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

abstract class CargoNdkBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val coreSrc: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val cargoToml: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val cargoLock: RegularFileProperty
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val platform: Property<String>
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:Internal abstract val coreDir: DirectoryProperty
    @get:OutputDirectory abstract val jniLibsDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = jniLibsDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = coreDir.get().asFile
            environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            val args = mutableListOf("cargo", "ndk")
            abis.get().forEach { args += listOf("-t", it) }
            args += listOf("--platform", platform.get(), "-o", out.absolutePath, "build", "--release", "--lib")
            commandLine(args)
        }
    }
}

abstract class UniffiKotlin @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
    @get:Internal abstract val coreDir: DirectoryProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outDir.get().asFile
        out.deleteRecursively()
        exec.exec {
            workingDir = coreDir.get().asFile
            commandLine(
                "cargo", "run", "--features", "cli", "--bin", "uniffi-bindgen", "--",
                "generate", "--library", library.get().asFile.absolutePath,
                "--language", "kotlin", "--out-dir", out.absolutePath, "--no-format",
            )
        }
    }
}

val cargoNdkBuild = tasks.register<CargoNdkBuild>("cargoNdkBuild") {
    description = "Builds the Rust core for the Android ABIs with cargo-ndk"
    group = "build"
    coreSrc.set(coreDirectory.dir("src"))
    cargoToml.set(coreDirectory.file("Cargo.toml"))
    cargoLock.set(coreDirectory.file("Cargo.lock"))
    abis.set(abiList)
    platform.set(minSdkLevel)
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    coreDir.set(coreDirectory)
    jniLibsDir.set(layout.buildDirectory.dir("generated/rustJniLibs"))
}

val generateUniffiKotlin = tasks.register<UniffiKotlin>("generateUniffiKotlin") {
    description = "Generates the Kotlin UniFFI bindings from the compiled core library"
    group = "build"
    library.set(cargoNdkBuild.flatMap { it.jniLibsDir.file("$bindgenAbi/libqal_keyboard_core.so") })
    coreDir.set(coreDirectory)
    outDir.set(layout.buildDirectory.dir("generated/uniffiKotlin"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::jniLibsDir)
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateUniffiKotlin, UniffiKotlin::outDir)
}

// Host build of the core: JVM unit tests load it through JNA.
val hostLibDir = coreDirectory.dir("target/release")
val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    description = "Builds the Rust core for the host so JVM unit tests can call it"
    group = "build"
    workingDir = coreDirectory.asFile
    commandLine("cargo", "build", "--release", "--lib")
    inputs.dir(coreDirectory.dir("src"))
    inputs.file(coreDirectory.file("Cargo.toml"))
    inputs.file(coreDirectory.file("Cargo.lock"))
    outputs.dir(hostLibDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    systemProperty("jna.library.path", hostLibDir.asFile.absolutePath)
}

dependencies {
    // The generated bindings use JNA: the AAR carries the Android native dispatch library,
    // the plain JAR (host) is used by JVM unit tests.
    api(variantOf(libs.jna.android) { artifactType("aar") })
    testImplementation(libs.jna.jvm)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
