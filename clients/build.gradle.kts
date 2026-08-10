plugins {
    application
}

dependencies {
    implementation(project(":common"))
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.demo.amps.clients.DemoRunner")
    applicationName = "amps-demo"
}

// `./gradlew :clients:run --args="sow-query --filter ..."` needs a console, and
// demos that stream messages need to be interruptible.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // Demos read AMPS_* / -Damps.* settings; forward anything the user set.
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("amps.") }
            .associateWith { System.getProperty(it) }
    )
    // The journal-size lab measures server/data, which is resolved relative to
    // the repository root rather than the module directory.
    workingDir = rootProject.projectDir
}
