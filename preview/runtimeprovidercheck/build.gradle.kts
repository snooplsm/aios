plugins {
    id("com.android.application")
}

android {
    namespace = "com.aios.runtime.smoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aios.modelbroker"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-runtime-smoke"
    }

    sourceSets {
        getByName("main") {
            aidl.directories.add("../../services/modelbroker/aidl")
            aidl.directories.add("../../services/runtimeapi/aidl")
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
