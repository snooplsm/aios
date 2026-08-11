plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        java.srcDir("../../runtime/whisperprovider/app/src/main/java")
    }
    test {
        java.srcDir("../../runtime/whisperprovider/app/src/test/java")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}

tasks.register("assembleDebug") {
    dependsOn(tasks.jar)
}

tasks.register("testDebugUnitTest") {
    dependsOn(tasks.test)
}

tasks.register("lintDebug") {
    dependsOn(tasks.check)
}
