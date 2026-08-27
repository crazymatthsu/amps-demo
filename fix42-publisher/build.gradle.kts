/**
 * FIX 4.2 delta publisher: a Spring Boot application that turns realistic
 * FIX 4.2 order flow into AMPS delta publishes, against the SOW topics
 * declared by the `fix42-chaining` server flow.
 *
 * Spring Boot earns its place here for one reason: every rule about WHICH
 * tags leave the publisher is configuration, not code (see
 * src/main/resources/application.yml). @ConfigurationProperties binding,
 * validation and profile overrides are the feature being used; the web stack
 * is deliberately absent (spring-boot-starter, not -starter-web).
 *
 * Two test suites, split because one needs a server and the other does not:
 *
 *   test              unit tests. Message construction, chain linkage, field
 *                     selection, config validation. No AMPS, runs in `build`.
 *   integrationTest   the real thing: starts an AMPS container on the
 *                     fix42-chaining flow, publishes, and queries the SOW
 *                     back. Skipped automatically when no image is available,
 *                     so `./gradlew build` still passes on a machine that has
 *                     never seen AMPS.
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

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Publish into a real AMPS instance and read the SOW back."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // The harness starts its own container, so it needs to know which image
    // and engine to use. Forwarded rather than hard-coded: there is no public
    // AMPS image, so the value is necessarily site-specific.
    listOf("AMPS_IMAGE", "CONTAINER_ENGINE", "AMPS_PLATFORM", "AMPS_BIN").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
    // The benchmark is not a test -- it asserts nothing and takes ~30s. Excluded
    // rather than left to skip itself, so a normal build reports ZERO skipped
    // tests: "0 skipped" is how you tell the integration suite actually ran
    // rather than opting out for want of an AMPS_IMAGE, and a permanently
    // skipped class would blunt that signal.
    filter { excludeTestsMatching("*Benchmark") }

    // Config and flow files are found relative to the repository root.
    workingDir = rootProject.projectDir
    testLogging {
        showStandardStreams = true
    }
}

/**
 * Publish throughput, measured rather than guessed:
 * `./gradlew :fix42-publisher:publishBenchmark`.
 *
 * Starts its own AMPS container and times the same load several ways -- per
 * message flush, single flush, various setPublishBatching sizes, and through
 * the real publisher at two log levels. Numbers are hardware- and
 * network-specific, which is the point: run it where you actually publish.
 */
tasks.register<Test>("publishBenchmark") {
    group = "verification"
    description = "Measure publish throughput against a real AMPS instance."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    workingDir = rootProject.projectDir
    filter { includeTestsMatching("*Benchmark") }
    systemProperty("fix42.benchmark", "true")
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }

    listOf("AMPS_IMAGE", "AMPS_BIN").forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }
}

// `check` runs it, but the test itself opts out when no AMPS image is
// configured -- so this stays green on a laptop without one, and does real
// work on a machine that has one.
tasks.named("check") {
    dependsOn(integrationTestTask)
}

// A runnable jar is not the point of this module -- the demo entry point is
// `./gradlew :fix42-publisher:bootRun`. Building one anyway costs a step on
// every build for an artifact nothing consumes.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}
tasks.named<Jar>("jar") {
    enabled = true
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    // Point the publisher somewhere else without editing YAML:
    //   ./gradlew :fix42-publisher:bootRun -Dfix42.amps.uri=tcp://host:9007/amps/fix
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("fix42.") || it.startsWith("amps.") }
            .associateWith { System.getProperty(it) }
    )
}
