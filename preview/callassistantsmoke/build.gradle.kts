import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedPolicy = layout.buildDirectory.dir("generated/call-assistant-policy/main")

val stageProductionPolicy by tasks.registering(Sync::class) {
    from("../../services/callintelligence/src") {
        include("com/aios/callintelligence/AnswerDelayPolicy.java")
        include("com/aios/callintelligence/CallPolicyEngine.java")
    }
    into(generatedPolicy)
}

android {
    namespace = "com.aios.callintelligence"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.callintelligence"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-emulator-smoke"
    }

    sourceSets {
        getByName("main") {
            java.directories.add(generatedPolicy.get().asFile.absolutePath)
            aidl.directories.add("../../services/callintelligence/aidl")
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.named("preBuild").configure {
    dependsOn(stageProductionPolicy)
}
