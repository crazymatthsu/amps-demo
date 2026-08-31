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
    mainClass.set("com.demo.amps.cli.AmpsCli")
    applicationName = "amps-cli"
}

// `./gradlew :amps-cli:run --args="..."` needs a console, and
// snapshot-subscribe should be interruptible with Ctrl-C.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("amps.") }
            .associateWith { System.getProperty(it) }
    )
    workingDir = rootProject.projectDir
}
