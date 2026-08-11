plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.aios.tools.emulatorcontrol.EmulatorControlMain")
}

dependencies {
    implementation("io.grpc:grpc-okhttp:1.69.1")
    implementation("io.grpc:grpc-stub:1.69.1")
    testImplementation("junit:junit:4.13.2")
}
