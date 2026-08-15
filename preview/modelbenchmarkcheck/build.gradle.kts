import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
}

val generatedMain = layout.buildDirectory.dir("generated/model-benchmark/main")
val generatedAndroidTest = layout.buildDirectory.dir("generated/model-benchmark/androidTest")
val generatedTest = layout.buildDirectory.dir("generated/model-benchmark/test")

val stageModelBenchmarkMain by tasks.registering(Sync::class) {
    from("../../benchmarks/modeladmission/app/src") {
        include("com/aios/modelbenchmark/**/*.java")
    }
    into(generatedMain)
}

val stageModelBenchmarkAndroidTest by tasks.registering(Sync::class) {
    from("../../benchmarks/modeladmission/common") {
        include("com/aios/modelbenchmark/**/*.java")
    }
    from("../../benchmarks/modeladmission/tests/src") {
        include("com/aios/modelbenchmark/**/*.java")
    }
    into(generatedAndroidTest)
}

val stageModelBenchmarkTest by tasks.registering(Sync::class) {
    from("../../benchmarks/modeladmission/common") {
        include("com/aios/modelbenchmark/**/*.java")
    }
    from("../../benchmarks/modeladmission/hosttests") {
        include("com/aios/modelbenchmark/**/*Test.java")
    }
    into(generatedTest)
}

android {
    namespace = "com.aios.modelbenchmark"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.modelbenchmark.compilecheck"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-compilecheck"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("src/main/AndroidManifest.xml")
            java.directories.add(generatedMain.get().asFile.absolutePath)
            aidl.directories.add("../../services/modelbroker/aidl")
        }
        getByName("androidTest") {
            manifest.srcFile("src/androidTest/AndroidManifest.xml")
            java.directories.add(generatedAndroidTest.get().asFile.absolutePath)
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

tasks.named("preBuild").configure {
    dependsOn(stageModelBenchmarkMain)
}

tasks.configureEach {
    if (name.contains("AndroidTest") && name != "stageModelBenchmarkAndroidTest") {
        dependsOn(stageModelBenchmarkAndroidTest)
    }
    if (name.contains("UnitTest") && name != "stageModelBenchmarkTest") {
        dependsOn(stageModelBenchmarkTest)
    }
}
