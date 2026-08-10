import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedMain = layout.buildDirectory.dir("generated/media-scan/main")
val generatedTest = layout.buildDirectory.dir("generated/media-scan/test")

val stageMediaScanMain by tasks.registering(Sync::class) {
    from("../../services/mediaintelligence/src") {
        include("com/aios/mediaintelligence/**/*.java")
    }
    into(generatedMain)
}

val stageMediaScanTest by tasks.registering(Sync::class) {
    from("../../services/mediaintelligence/tests/src") {
        include("com/aios/mediaintelligence/**/*Test.java")
    }
    into(generatedTest)
}

android {
    namespace = "com.aios.mediaintelligence"
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
            manifest.srcFile("../../services/mediaintelligence/AndroidManifest.xml")
            java.directories.add(generatedMain.get().asFile.absolutePath)
            java.directories.add("../../services/contextintelligence/api")
            res.directories.add("../../services/mediaintelligence/res")
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
