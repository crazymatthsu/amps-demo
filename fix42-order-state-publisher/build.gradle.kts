/**
 * FIX 4.2 order-state publisher: the stateful successor to fix42-publisher.
 * Same drop-copy flow, same audit topics, but the blotter record is keyed and
 * merged by the PUBLISHER from a small in-memory cache, so a message can be
 * applied conditionally -- which a server-side last-writer-wins merge cannot
 * do. The design is in docs/; docs/05 is the plan this build follows.
 *
 * Self-contained by design: the FIX layer and the mock venue are COPIES of
 * fix42-publisher's, not a dependency, so either module can be lifted out of
 * this repository alone. The only in-repository dependency is the test-scope
 * amps-test-harness.
 *
 * Two test suites, split because one needs a server and the other does not:
 *
 *   test              unit tests: the FIX layer, the mock venue, the state
 *                     machine and its record encoding. No AMPS, runs in `build`.
 *   integrationTest   publishes into an AMPS container running the
 *                     fix42-order-state flow and reads the SOW back. Skipped
 *                     automatically when no image is configured.
 */
plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.amps.client)
    implementation(libs.slf4j.api)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
}

/**
 * The integration suite compiles against the main source set and the unit-test
 * helpers, but runs on its own task so a plain `build` never needs a container.
 */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "integrationTestImplementation"(project(":amps-test-harness"))
    // The exposure views are json-typed; their records come back as JSON.
    "integrationTestImplementation"(libs.gson)
}

/**
 * The environment the harness reads, declared as task INPUTS and not merely
 * forwarded: this repository sets org.gradle.caching=true, and a variable the
 * cache key cannot see lets a run without AMPS_IMAGE cache an all-skipped
 * result that a later run WITH it restores. fix42-publisher's build explains
 * the failure at length; the remedy is the same here.
 */
val harnessEnvironment = listOf(
    "AMPS_IMAGE", "AMPS_BIN", "AMPS_TEST_HARNESS",
    // The CLI harness.
    "CONTAINER_ENGINE", "AMPS_PLATFORM",
    // The Testcontainers harness.
    "DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED",
)

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Publish into a real AMPS instance and read the SOW back."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    harnessEnvironment.forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }
    filter { excludeTestsMatching("*Benchmark") }
    // Config and flow files are found relative to the repository root.
    workingDir = rootProject.projectDir
    testLogging {
        showStandardStreams = true
    }
}

// `check` runs it; the suite itself opts out when no AMPS image is configured.
tasks.named("check") {
    dependsOn(integrationTestTask)
}

// The demo entry point is bootRun; nothing consumes a runnable jar.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    // Point the publisher somewhere else without editing YAML:
    //   ./gradlew :fix42-order-state-publisher:bootRun -Dfix42-order-state.amps.uri=tcp://host:9007/amps/fix
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("fix42-order-state.") || it.startsWith("amps.") || it.startsWith("spring.") }
            .associateWith { System.getProperty(it) }
    )
}
