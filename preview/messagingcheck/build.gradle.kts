import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedPlatform = layout.buildDirectory.dir("generated/messaging-platform/main")

val stageMessagingPlatform by tasks.registering(Sync::class) {
    from("../../apps/messaging/platform/src") {
        include("com/aios/messaging/mms/platform/MmsOperationStore.kt")
    }
    into(generatedPlatform)
}

android {
    namespace = "com.aios.messaging"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.messaging.compilecheck"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-compilecheck"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../apps/messaging/AndroidManifest.xml")
            kotlin.directories.add("../../apps/messaging/src")
            kotlin.directories.add("src/main/java")
            kotlin.directories.add(generatedPlatform.get().asFile.absolutePath)
            java.directories.add("../../services/contextintelligence/api")
            aidl.directories.add("../../services/contextintelligence/aidl")
            aidl.directories.add("../../services/mediaintelligence/aidl")
            res.directories.add("../../apps/messaging/res")
        }
        getByName("test") {
            kotlin.directories.add("../../apps/messaging/tests/src")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageMessagingPlatform)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
