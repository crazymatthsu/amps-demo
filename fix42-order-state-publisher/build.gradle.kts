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

/**
 * The core is a library that does not care where its FIX comes from or where
 * its records go (docs/07 §3): the packages below may import only the JDK,
 * SLF4J and each other. AMPS lives in `amps`, Spring in the application, the
 * `amps` package and `config/OrderStateProperties`, which binds the yml and is
 * the one class under `config` left out. A fully-qualified name in code counts
 * as an import: an import-only check misses `com.crankuptheamps.client.fields.Field`
 * written inline.
 */
val corePackages = listOf("fix", "machine", "spi", "hooks", "publish", "recovery", "config")
val checkCoreSelfContained = tasks.register("checkCoreSelfContained") {
    group = "verification"
    description = "Verify the core packages import only the JDK, SLF4J and each other."

    val root = layout.projectDirectory.dir("src/main/java/com/demo/amps/fix42/state").asFile
    val dirs = corePackages.map { File(root, it) }
    dirs.forEach { inputs.dir(it) }
    outputs.upToDateWhen { false }

    doLast {
        val excluded = setOf("config/OrderStateProperties.java")
        val allowed = listOf("java.", "org.slf4j.") + corePackages.map { "com.demo.amps.fix42.state.$it." }
        val importLine = Regex("""^\s*import\s+(?:static\s+)?([\w.]+)""")
        // A qualified name in code: two or more dotted lower-case segments, then more.
        val qualifiedName = Regex("""(?<![\w.])((?:com|org|net|io|jakarta|javax)\.[a-z]\w*(?:\.\w+)+)""")
        val problems = mutableListOf<String>()
        var scanned = 0
        dirs.forEach { dir ->
            val files = dir.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.path }.toList()
            if (files.isEmpty()) {
                problems += "no sources under ${dir.relativeTo(projectDir)}"
            }
            files.filter { it.relativeTo(root).invariantSeparatorsPath !in excluded }.forEach { file ->
                scanned++
                var inComment = false
                file.readLines().forEachIndexed { index, line ->
                    val where = "${file.relativeTo(projectDir)}:${index + 1}"
                    val imported = importLine.find(line)?.groupValues?.get(1)
                    if (imported != null) {
                        if (allowed.none { imported.startsWith(it) }) {
                            problems += "$where: imports $imported"
                        }
                        return@forEachIndexed
                    }
                    // Code only: javadoc and comments may name what lives outside.
                    var code = line
                    if (inComment) {
                        val end = code.indexOf("*/")
                        if (end < 0) return@forEachIndexed
                        code = code.substring(end + 2)
                        inComment = false
                    }
                    code = code.replace(Regex("""/\*.*?\*/"""), "")
                    val open = code.indexOf("/*")
                    if (open >= 0) {
                        code = code.substring(0, open)
                        inComment = true
                    }
                    code = code.substringBefore("//")
                    if (code.trimStart().startsWith("package ")) return@forEachIndexed
                    qualifiedName.findAll(code).forEach { match ->
                        val name = match.groupValues[1]
                        if (allowed.none { name.startsWith(it) }) {
                            problems += "$where: refers to $name"
                        }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("the fix42-order-state core is not self-contained (docs/07 §3):\n  "
                + problems.joinToString("\n  "))
        }
        logger.lifecycle("fix42-order-state-publisher: $scanned core sources depend only on the JDK, SLF4J and each other")
    }
}

tasks.named("check") {
    dependsOn(checkModuleDocs, checkCoreSelfContained)
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
