import groovy.json.JsonOutput
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aios.runtime.whispercpp"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.aios.runtime.whispercpp"
        minSdk = 35
        targetSdk = 36
        versionCode = 10902
        versionName = "1.9.2"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { cppFlags += listOf("-std=c++17") }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    sourceSets["main"].aidl.srcDirs(
        "../../../services/modelbroker/aidl",
        "../../../services/runtimeapi/aidl",
    )

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/*.kotlin_module")
    }
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
            .distinctBy { it["coordinate"] }
            .sortedBy { it["coordinate"].toString() }
        val provenance = mapOf(
            "schema_version" to 1,
            "runtime" to "whisper_cpp",
            "provider_package" to "com.aios.runtime.whispercpp",
            "provider_service" to "com.aios.runtime.whispercpp.WhisperRuntimeService",
            "implementation_version" to "1.9.2",
            "source_repository" to "https://github.com/ggml-org/whisper.cpp",
            "source_revision" to "306c88f4d1286aec1bf96e544632897886af5501",
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
