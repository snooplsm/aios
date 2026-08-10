import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedMain = layout.buildDirectory.dir("generated/call-context/main")
val generatedTest = layout.buildDirectory.dir("generated/call-context/test")

val stageCallContextMain by tasks.registering(Sync::class) {
    from("../../services/contextintelligence/api") {
        include("com/aios/context/**/*.java")
    }
    from("../../services/contextintelligence/src") {
        include("com/aios/contextintelligence/**/*.java")
    }
    into(generatedMain)
}

val stageCallContextTest by tasks.registering(Sync::class) {
    from("../../services/contextintelligence/tests/src") {
        include("com/aios/contextintelligence/**/*Test.java")
    }
    into(generatedTest)
}

android {
    namespace = "com.aios.contextintelligence"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.callcontextcheck"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-compilecheck"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../services/contextintelligence/AndroidManifest.xml")
            java.directories.add(generatedMain.get().asFile.absolutePath)
            res.directories.add("../../services/contextintelligence/res")
            aidl.directories.add("../../services/contextintelligence/aidl")
        }
        getByName("test") {
            java.directories.add(generatedTest.get().asFile.absolutePath)
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.named("preBuild").configure {
    dependsOn(stageCallContextMain)
}

tasks.configureEach {
    if (name.contains("UnitTest")) {
        dependsOn(stageCallContextTest)
    }
}
