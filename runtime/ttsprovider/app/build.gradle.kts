import groovy.json.JsonOutput
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sherpaAar = rootProject.file("third_party/sherpa-onnx-1.13.4.aar")
val noticeAssets = rootProject.file("third_party/notices")

android {
    namespace = "com.aios.runtime.sherpatts"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.runtime.sherpatts"
        minSdk = 35
        targetSdk = 36
        versionCode = 11307
        versionName = "1.13.8"
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
    sourceSets["main"].assets.srcDir(noticeAssets)

    buildTypes {
        debug {
            buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "true")
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            buildConfigField("boolean", "ALLOW_EMULATOR_MODEL_FIXTURES", "false")
            ndk { abiFilters += "arm64-v8a" }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(files(sherpaAar))
}

dependencyLocking { lockAllConfigurations() }

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

tasks.register("verifyPinnedInputs") {
    inputs.files(sherpaAar, noticeAssets)
    doLast {
        require(sherpaAar.isFile && sherpaAar.length() == 48_847_529L)
        require(sha256(sherpaAar) ==
            "03f9c4df965f21c71269365a7951a7f23b5696fddd093fa318c80d65550ab780")
        val sherpaLicense = File(noticeAssets,
            "THIRD_PARTY_NOTICES/sherpa-onnx-LICENSE.txt")
        val ortLicense = File(noticeAssets,
            "THIRD_PARTY_NOTICES/onnxruntime-LICENSE.txt")
        require(sherpaLicense.length() == 11_358L && sha256(sherpaLicense) ==
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30")
        require(ortLicense.length() == 1_073L && sha256(ortLicense) ==
            "2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c")
    }
}

tasks.named("preBuild").configure { dependsOn("verifyPinnedInputs") }

tasks.register("writeRuntimeProvenance") {
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
            .filter { it["coordinate"] != "unspecified:sherpa-onnx-1.13.4:unspecified" }
            .plus(mapOf(
                "coordinate" to "github-release:k2-fsa-sherpa-onnx:1.13.4",
                "sha256" to sha256(sherpaAar),
                "size_bytes" to sherpaAar.length(),
            ))
            .distinctBy { it["coordinate"] }
            .sortedBy { it["coordinate"].toString() }
        val provenance = mapOf(
            "schema_version" to 1,
            "runtime" to "sherpa_onnx_tts",
            "provider_package" to "com.aios.runtime.sherpatts",
            "provider_service" to
                "com.aios.runtime.sherpatts.SherpaTtsRuntimeService",
            "implementation_version" to "1.13.8",
            "source_repository" to "https://github.com/k2-fsa/sherpa-onnx",
            "source_revision" to "142807252687d81b40d6315f23470a1512a00de3",
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
