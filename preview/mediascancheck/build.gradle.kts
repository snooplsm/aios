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
            "com/aios/mediaintelligence/JpegXmpInjector.java",
            "com/aios/mediaintelligence/PngXmpInjector.java",
            "com/aios/mediaintelligence/MediaGenerationReconciler.java",
            "com/aios/mediaintelligence/MediaGenerationScanner.java",
            "com/aios/mediaintelligence/MediaAssociationPolicy.java",
            "com/aios/mediaintelligence/MediaContextAssociationService.java",
            "com/aios/mediaintelligence/MediaContextProjection.java",
            "com/aios/mediaintelligence/MediaContent.java",
            "com/aios/mediaintelligence/MediaJobStore.java",
            "com/aios/mediaintelligence/MediaLivenessReconciler.java",
            "com/aios/mediaintelligence/MediaLivenessScanner.java",
            "com/aios/mediaintelligence/MediaBrokerClient.java",
            "com/aios/mediaintelligence/MediaConstraintProbe.java",
            "com/aios/mediaintelligence/MediaInferenceJobService.java",
            "com/aios/mediaintelligence/MediaInputPolicy.java",
            "com/aios/mediaintelligence/MediaMetadataCommitter.java",
            "com/aios/mediaintelligence/MediaObserverService.java",
            "com/aios/mediaintelligence/MediaTiming.java",
            "com/aios/mediaintelligence/MediaTimingSummary.java",
            "com/aios/mediaintelligence/MediaWorkPolicy.java",
            "com/aios/mediaintelligence/MediaResult.java",
            "com/aios/mediaintelligence/VideoAudioExtractor.java",
            "com/aios/mediaintelligence/VideoStoryboard.java",
            "com/aios/mediaintelligence/VideoStoryboardPlan.java",
            "com/aios/mediaintelligence/VideoTranscript.java",
            "com/aios/mediaintelligence/XmpProjection.java",
        )
    }
    into(generatedMain)
}

val stageMediaScanTest by tasks.registering(Sync::class) {
    from("../../services/mediaintelligence/tests/src") {
        include(
            "com/aios/mediaintelligence/JpegXmpInjectorTest.java",
            "com/aios/mediaintelligence/MediaGenerationReconcilerTest.java",
            "com/aios/mediaintelligence/MediaAssociationPolicyTest.java",
            "com/aios/mediaintelligence/MediaLivenessReconcilerTest.java",
            "com/aios/mediaintelligence/MediaTimingTest.java",
            "com/aios/mediaintelligence/MediaWorkPolicyTest.java",
            "com/aios/mediaintelligence/PngXmpInjectorTest.java",
            "com/aios/mediaintelligence/VideoStoryboardPlanTest.java",
            "com/aios/mediaintelligence/VideoTranscriptTest.java",
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
            java.directories.add("../../services/contextintelligence/api")
            aidl.directories.add("../../services/contextintelligence/aidl")
            aidl.directories.add("../../services/mediaintelligence/aidl")
            aidl.directories.add("../../services/modelbroker/aidl")
        }
        getByName("test") {
            java.directories.add(generatedTest.get().asFile.absolutePath)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
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
