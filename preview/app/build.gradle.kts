import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedMessaging = layout.buildDirectory.dir("generated/messaging-preview/main")

val stageMessagingPreviewSources by tasks.registering(Sync::class) {
    from("../../apps/messaging/src/com/aios/messaging/model") {
        include("**/*.kt")
    }
    from("../../apps/messaging/src/com/aios/messaging/ui") {
        include("MessagingScreens.kt")
    }
    from("../../apps/messaging/src/com/aios/messaging/ui/theme") {
        include("**/*.kt")
    }
    into(generatedMessaging)
}

android {
    namespace = "com.aios.phone.preview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.phone.preview"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-preview"
    }

    sourceSets {
        getByName("main") {
            kotlin.directories.addAll(listOf(
                "src/main/java",
                "../../apps/phone/src/com/aios/phone/model",
                "../../apps/phone/src/com/aios/phone/ui/screens",
                "../../apps/phone/src/com/aios/phone/ui/theme",
                generatedMessaging.get().asFile.absolutePath,
            ))
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageMessagingPreviewSources)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
