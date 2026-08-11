import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aios.runtime.litertlm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.runtime.litertlm"
        minSdk = 35
        targetSdk = 36
        versionCode = 15
        versionName = "0.15.0"
        buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "false")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].aidl.srcDirs(
        "../../../services/modelbroker/aidl",
        "../../../services/runtimeapi/aidl",
    )
    sourceSets["main"].java.srcDir("../../common/src/main/java")

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "true")
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.15.0")
}

dependencyLocking {
    lockAllConfigurations()
}

fun sha256(path: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    path.inputStream().buffered().use { stream ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

val generatedNoticeDirectory = layout.buildDirectory.dir("generated/runtimeNotices")
android.sourceSets["main"].assets.srcDir(generatedNoticeDirectory)

val extractRuntimeNotices = tasks.register("extractRuntimeNotices") {
    notCompatibleWithConfigurationCache(
        "Resolves the locked runtime classpath and extracts reviewed notices",
    )
    val runtimeClasspath = configurations.named("releaseRuntimeClasspath")
    inputs.files(runtimeClasspath)
    outputs.dir(generatedNoticeDirectory)
    doLast {
        val artifact = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
            .single { resolved ->
                val id = resolved.moduleVersion.id
                id.group == "com.google.ai.edge.litertlm"
                    && id.name == "litertlm-android" && id.version == "0.15.0"
            }.file
        require(artifact.length() == 19_827_303L
            && sha256(artifact) ==
                "b398c4745934a6035d192ffce5fdaf4f72a0009830a97b73c017c21f2a92b5bd") {
            "LiteRT-LM AAR does not match the reviewed catalog artifact"
        }
        val destination = generatedNoticeDirectory.get().asFile
            .resolve("THIRD_PARTY_NOTICES")
        destination.deleteRecursively()
        destination.mkdirs()
        val notices = listOf(
            Triple(
                "LICENSE",
                "LiteRT-LM-LICENSE.txt",
                "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
            ),
            Triple(
                "THIRD_PARTY_NOTICE.txt",
                "LiteRT-LM-THIRD_PARTY_NOTICE.txt",
                "0b2c3c75da43d624e40d6e8072340792ef054ca88a003b5dfcb3b129f21bb65b",
            ),
        )
        ZipFile(artifact).use { archive ->
            notices.forEach { (sourceName, destinationName, expectedDigest) ->
                val entry = requireNotNull(archive.getEntry(sourceName)) {
                    "LiteRT-LM AAR lacks $sourceName"
                }
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                require(sha256(bytes) == expectedDigest) {
                    "LiteRT-LM notice changed: $sourceName"
                }
                destination.resolve(destinationName).writeBytes(bytes)
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(extractRuntimeNotices) }

tasks.register("writeRuntimeProvenance") {
    notCompatibleWithConfigurationCache(
        "Resolves the locked runtime classpath and writes provenance",
    )
    dependsOn("assembleRelease")
    doLast {
        val verification = rootProject.file("gradle/verification-metadata.xml")
        require(verification.isFile) {
            "strict Gradle dependency verification metadata is absent"
        }
        val dependencies = configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .map { artifact ->
                val id = artifact.moduleVersion.id
                mapOf(
                    "coordinate" to "${id.group}:${id.name}:${id.version}",
                    "sha256" to sha256(artifact.file),
                    "size_bytes" to artifact.file.length(),
                )
            }
            .distinctBy { it["coordinate"] }
            .sortedBy { it["coordinate"].toString() }
        val provenance = mapOf(
            "schema_version" to 1,
            "runtime" to "litert_lm",
            "provider_package" to "com.aios.runtime.litertlm",
            "provider_service" to
                "com.aios.runtime.litertlm.LiteRtLmRuntimeService",
            "implementation_version" to "0.15.0",
            "source_repository" to "https://github.com/google-ai-edge/LiteRT-LM",
            "source_revision" to "2117fc4314670e00047bc8469783f02a68c33f0c",
            "reproducible_build_command" to
                "gradle --offline --no-daemon --dependency-verification=strict " +
                ":app:writeRuntimeProvenance",
            "dependency_verification_sha256" to sha256(verification),
            "resolved_dependencies" to dependencies,
        )
        val destination = rootProject.layout.buildDirectory
            .file("runtime-provenance.json").get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(provenance)) + "\n")
        println("Wrote ${destination.absolutePath}")
    }
}
