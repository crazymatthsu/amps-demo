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
    testImplementation(libs.jol.core)
}

// ChainMemoryTest sizes the cache with JOL, which on JDK 21 reads a record's
// field offsets only this way (the cache holds records: options, pending requests).
tasks.test {
    systemProperty("jol.magicFieldOffset", "true")
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

/**
 * The live heap a day's orders take, measured at scale:
 * `./gradlew :fix42-order-state-publisher:scaleMemory`.
 *
 * A million orders per scenario through the machine, in a JVM of its own with
 * a 16 GB heap (the test workers' is too small), the heap read after full
 * collections. Compare JVM flags with -PscaleJvmArgs (for example
 * -XX:-UseCompressedOops, as a heap above 32 GB runs) and the size with
 * -PscaleOrders. Not a test, so nothing runs it unasked.
 */
tasks.register<JavaExec>("scaleMemory") {
    group = "verification"
    description = "Measure the cache's live heap per order at a million orders."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.demo.amps.fix42.state.machine.ScaleMemoryMeasurement")
    maxHeapSize = "16g"
    jvmArgs((findProperty("scaleJvmArgs") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList<String>())
    systemProperty("scale.orders", findProperty("scaleOrders") ?: "1000000")
}

/**
 * The README and the design notes carry relative links, into each other and
 * into the code; a link to a file that does not exist is the rot the docs
 * module already refuses. The same check fix-pub-seqno runs, for this module.
 */
val checkModuleDocs = tasks.register("checkModuleDocs") {
    group = "verification"
    description = "Verify the relative links in this module's markdown resolve."

    val moduleDir = layout.projectDirectory.asFile
    inputs.dir(layout.projectDirectory.dir("docs"))
    inputs.file(layout.projectDirectory.file("README.md"))
    outputs.upToDateWhen { false }

    doLast {
        val linkPattern = Regex("""\[[^]]*]\((?!https?://|#)([^)#]+)(?:#[^)]*)?\)""")
        val documents = listOf(File(moduleDir, "README.md")) +
            File(moduleDir, "docs").listFiles { file -> file.extension == "md" }.orEmpty().sorted()
        val problems = mutableListOf<String>()
        documents.forEach { document ->
            linkPattern.findAll(document.readText()).forEach { match ->
                val target = match.groupValues[1].trim()
                if (!File(document.parentFile, target).exists()) {
                    problems += "${document.name}: broken link -> $target"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("documentation problems:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("fix42-order-state-publisher: ${documents.size} documents, links resolve")
    }
}

tasks.named("check") {
    dependsOn(checkModuleDocs)
}

/**
 * What the cache and the publish store cost, measured rather than argued:
 * `./gradlew :fix42-order-state-publisher:publishBenchmark`.
 *
 * Starts its own AMPS container and times fix42-publisher's benchmark load
 * through the machine alone and through the publisher with each publish-store
 * value; `./gradlew :fix42-publisher:publishBenchmark` on the same machine is
 * the comparison. Not a test: it asserts nothing, so integrationTest excludes
 * it and a normal build still reports zero skipped.
 */
tasks.register<Test>("publishBenchmark") {
    group = "verification"
    description = "Measure throughput through the machine and per publish store against a real AMPS instance."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    workingDir = rootProject.projectDir
    filter { includeTestsMatching("*Benchmark") }
    systemProperty("fix42-order-state.benchmark", "true")
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    harnessEnvironment.forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
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
    // The demo profile (docs/04 §10): a memory publish store and strict mode.
    // -Dspring.profiles.active=... on the command line wins.
    systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active") ?: "demo")
    // Point the publisher somewhere else without editing YAML:
    //   ./gradlew :fix42-order-state-publisher:bootRun -Dfix42-order-state.amps.uri=tcp://host:9007/amps/fix
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("fix42-order-state.") || it.startsWith("amps.") || it.startsWith("spring.") }
            .associateWith { System.getProperty(it) }
    )
}
