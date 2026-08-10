import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedMain = layout.buildDirectory.dir("generated/model-service/main")
val generatedTest = layout.buildDirectory.dir("generated/model-service/test")

val stageModelServiceMain by tasks.registering(Sync::class) {
    from("../../services/modelbroker/src") {
        include("com/aios/modelbroker/**/*.java")
        exclude("com/aios/modelbroker/BrokerProductProperties.java")
    }
    into(generatedMain)
}

val stageModelServiceTest by tasks.registering(Sync::class) {
    from("../../services/modelbroker/tests/src") {
        include("com/aios/modelbroker/**/*Test.java")
    }
    into(generatedTest)
}

android {
    namespace = "com.aios.modelbroker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.modelbroker.compilecheck"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-compilecheck"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../services/modelbroker/AndroidManifest.xml")
            java.directories.add(generatedMain.get().asFile.absolutePath)
            aidl.directories.add("../../services/modelbroker/aidl")
            aidl.directories.add("../../services/runtimeapi/aidl")
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
    dependsOn(stageModelServiceMain)
}

tasks.configureEach {
    if (name.contains("UnitTest")) {
        dependsOn(stageModelServiceTest)
    }
}
