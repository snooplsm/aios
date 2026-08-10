import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedMain = layout.buildDirectory.dir("generated/call-service/main")
val generatedTest = layout.buildDirectory.dir("generated/call-service/test")

val stageCallServiceMain by tasks.registering(Sync::class) {
    from("../../services/callintelligence/src") {
        include("com/aios/callintelligence/**/*.java")
        exclude("com/aios/callintelligence/CallProductProperties.java")
    }
    from("../../services/contextintelligence/api") {
        include("com/aios/context/**/*.java")
    }
    into(generatedMain)
}

val stageCallServiceTest by tasks.registering(Sync::class) {
    from("../../services/callintelligence/tests/src") {
        include("com/aios/callintelligence/**/*Test.java")
    }
    into(generatedTest)
}

android {
    namespace = "com.aios.callintelligence"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.callintelligence.compilecheck"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-compilecheck"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../services/callintelligence/AndroidManifest.xml")
            java.directories.add(generatedMain.get().asFile.absolutePath)
            aidl.directories.add("../../services/callintelligence/aidl")
            aidl.directories.add("../../services/contextintelligence/aidl")
            aidl.directories.add("../../services/modelbroker/aidl")
        }
        getByName("test") {
            java.directories.add(generatedTest.get().asFile.absolutePath)
        }
    }

    buildFeatures {
        aidl = true
    }

    lint {
        // This compile-only APK is intentionally not privileged; the Soong app is.
        disable += "ProtectedPermissions"
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
    dependsOn(stageCallServiceMain)
}

tasks.configureEach {
    if (name.contains("UnitTest")) {
        dependsOn(stageCallServiceTest)
    }
}
