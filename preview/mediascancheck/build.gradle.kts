import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedMain = layout.buildDirectory.dir("generated/media-scan/main")
val generatedTest = layout.buildDirectory.dir("generated/media-scan/test")

val stageMediaScanMain by tasks.registering(Sync::class) {
    from("../../services/mediaintelligence/src") {
        include(
            "com/aios/mediaintelligence/CaptureCoalescer.java",
            "com/aios/mediaintelligence/MediaGenerationReconciler.java",
            "com/aios/mediaintelligence/MediaGenerationScanner.java",
            "com/aios/mediaintelligence/MediaJobStore.java",
            "com/aios/mediaintelligence/MediaObserverService.java",
            "com/aios/mediaintelligence/MediaTiming.java",
            "com/aios/mediaintelligence/MediaTimingSummary.java",
            "com/aios/mediaintelligence/MediaWorkPolicy.java",
        )
    }
    into(generatedMain)
}

val stageMediaScanTest by tasks.registering(Sync::class) {
    from("../../services/mediaintelligence/tests/src") {
        include(
            "com/aios/mediaintelligence/MediaGenerationReconcilerTest.java",
            "com/aios/mediaintelligence/MediaTimingTest.java",
            "com/aios/mediaintelligence/MediaWorkPolicyTest.java",
        )
    }
    into(generatedTest)
}

android {
    namespace = "com.aios.mediascancheck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.mediascancheck"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-compilecheck"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.directories.add(generatedMain.get().asFile.absolutePath)
        }
        getByName("test") {
            java.directories.add(generatedTest.get().asFile.absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild").configure {
    dependsOn(stageMediaScanMain)
}

tasks.configureEach {
    if (name.contains("UnitTest")) {
        dependsOn(stageMediaScanTest)
    }
}
