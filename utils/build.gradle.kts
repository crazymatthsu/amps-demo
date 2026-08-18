/**
 * Standalone AMPS tools: get a file in, get data out, clear it down.
 *
 * These are operator tools rather than teaching material -- the demos in
 * `clients/` explain AMPS, these just do a job on a real instance. That
 * difference drives the design:
 *
 *   - output on stdout is a progress report, the payload always goes to a file
 *   - the dump formats round-trip, so ampsToFileSOW output is valid
 *     fileToAMPS input
 *   - anything destructive refuses to run without --yes
 */
plugins {
    application
}

dependencies {
    implementation(project(":common"))
    implementation(libs.gson)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.demo.amps.utils.UtilsRunner")
    applicationName = "amps-utils"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("amps.") }
            .associateWith { System.getProperty(it) }
    )
    // Relative --file/--out paths should mean what the caller meant, and the
    // shell wrappers are normally invoked from the repository root.
    workingDir = rootProject.projectDir
}

/**
 * The shell wrappers call the installed launcher, so `installDist` is what
 * actually makes them work. Wiring it into `build` means a plain `./gradlew
 * build` leaves the tools ready to run.
 */
tasks.named("build") {
    dependsOn(tasks.named("installDist"))
}
