import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

// :api (A2.1): the typed API client. Plain Kotlin/JVM (no Android dependency) so the whole
// transport layer, including the interceptors and the authenticator, is tested on the JVM.
// The generated client is produced at build time from contracts/openapi.yaml and never committed.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-opt-in=kotlinx.serialization.ExperimentalSerializationApi")
    }
}

val contractFile = rootProject.layout.projectDirectory.file("../contracts/openapi.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")
val normalizedContract = layout.buildDirectory.file("generated/openapi-input/openapi.yaml")

// OpenAPI Generator 7.14 (kotlin) mishandles two 3.1 constructs the contract uses. The contract
// itself is not edited; this task writes a normalised copy into build/ (nothing is committed):
//  1. `const: true` on a boolean becomes an enum with a String value that does not compile.
//     The `const` is dropped (validation only; the client always sends `true` where required).
//  2. `oneOf: [{required: [a]}, {required: [b]}]` next to `properties` makes the generator emit
//     an EMPTY data class (InvitationCreate, StorePurchaseRequest, TranscriptionRequest,
//     PartnerTranscriptionRequest). Such a oneOf only expresses "exactly one of" (server side
//     validation), so the block is dropped and the properties are kept.
val normalizeContract = tasks.register("normalizeContract") {
    group = "openapi"
    description = "Writes build/generated/openapi-input/openapi.yaml: the contract minus two constructs the generator cannot handle."
    inputs.file(contractFile).withPropertyName("contract")
    outputs.file(normalizedContract)
    doLast {
        var text = contractFile.asFile.readText()
        text = text.replace(Regex(""", const: true \}"""), " }")
        text = text.replace(Regex("""(?m)^ +oneOf:\r?\n(?: +- required: \[[^\]]*\]\r?\n)+"""), "")
        val out = normalizedContract.get().asFile
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

// OpenAPI Generator "kotlin" + jvm-okhttp4 + kotlinx.serialization. The 3.1 contract is accepted
// as is (the generator logs a "3.1 is in beta" warning). dateLibrary=string keeps timestamps as
// ISO-8601 strings: java.time is missing below API 26 and the app's minSdk is 24, so no
// desugaring requirement is imposed on the app. Calls are blocking (OkHttp execute); the
// wrapper in ApiCalls.kt runs them on Dispatchers.IO.
val generateApiClient = tasks.register<GenerateTask>("generateApiClient") {
    group = "openapi"
    description = "Generates the Kotlin API client from contracts/openapi.yaml into build/generated/openapi."
    generatorName.set("kotlin")
    library.set("jvm-okhttp4")
    // A file URI: a plain Windows path is rejected by the generator's spec parser.
    dependsOn(normalizeContract)
    inputSpec.set(normalizedContract.get().asFile.toURI().toString())
    outputDir.set(generatedDir.map { it.asFile.absolutePath })
    packageName.set("com.qalsmartkeyboard.api.generated")
    apiPackage.set("com.qalsmartkeyboard.api.generated.apis")
    modelPackage.set("com.qalsmartkeyboard.api.generated.models")
    invokerPackage.set("com.qalsmartkeyboard.api.generated.infrastructure")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    skipValidateSpec.set(false)
    // The contract has a schema named ApiClient (partner API clients); it would collide with the
    // generated infrastructure.ApiClient.
    modelNameMappings.set(mapOf("ApiClient" to "PartnerApiClient"))
    // Free-form objects (`additionalProperties: true`) would be Map<String, Any>, which
    // kotlinx.serialization cannot serialise and which crashes the compiler back-end.
    typeMappings.set(mapOf("AnyType" to "JsonElement"))
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "dateLibrary" to "string",
            "omitGradleWrapper" to "true",
        ),
    )
}

sourceSets.main {
    kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
}
tasks.named("compileKotlin") { dependsOn(generateApiClient) }

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}

// Local-stack test (needs a running backend): excluded from `test`, run with
//   ./gradlew :api:localStackTest -PlocalStack=true [-PlocalStackUrl=http://localhost:8080]
//                                 [-PlocalStackOtpCode=123456]   (default: read from the backend's dev SMS capture)
val localStackEnabled = providers.gradleProperty("localStack").map { it.toBoolean() }.orElse(false)

tasks.test {
    useJUnit()
    exclude("**/localstack/**")
}

val localStackTest = tasks.register<Test>("localStackTest") {
    group = "verification"
    description = "Optional: exercises the client against a locally running backend. Needs -PlocalStack=true."
    useJUnit()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/localstack/**")
    onlyIf { localStackEnabled.get() }
    outputs.upToDateWhen { false }
    systemProperty("qal.localStack.url", providers.gradleProperty("localStackUrl").orElse("http://localhost:8080").get())
    providers.gradleProperty("localStackOtpCode").orNull?.let { systemProperty("qal.localStack.otpCode", it) }
    testLogging { events("passed", "failed", "skipped") }
}

// The CI Android job runs `testDebugUnitTest` (an Android task name). Alias it so the JVM tests
// of this module are part of that command.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Alias of :api:test so the CI command covers this module."
    dependsOn(tasks.test)
}
