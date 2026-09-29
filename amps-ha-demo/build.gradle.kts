/**
 * amps-ha-demo: a replicated AMPS pair (primary + secondary, synchronous
 * replication in both directions) and the two client shapes that survive
 * losing one of them -- a publisher on an HAClient with a publish store, a
 * consumer on an HAClient with a bookmark store -- plus the proof: an
 * integration test that kills an instance mid-stream and checks that every
 * message published arrived exactly once.
 *
 *   ./amps-ha-demo/scripts/ha-compose.sh start        # both instances in podman
 *   ./gradlew :amps-ha-demo:run --args="both"         # publisher + consumer, one JVM
 *   ./amps-ha-demo/scripts/ha-compose.sh failover     # SIGKILL the primary
 *
 * Two test suites, split the same way as the other modules':
 *
 *   test              unit tests: the sequence ledger, the record codec, the
 *                     settings parser. No AMPS, runs in `build`.
 *   integrationTest   the failover itself, on Testcontainers: two AMPS
 *                     containers on one network, the clients pointed at both,
 *                     the primary killed while the publisher is mid-stream,
 *                     then brought back and the secondary killed. Skipped
 *                     when AMPS_IMAGE is unset or no Docker API is reachable.
 */
plugins {
    application
}

dependencies {
    implementation(libs.amps.client)
    // The records are small JSON documents; Gson is what the other modules
    // already parse JSON with.
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.demo.amps.ha.HaDemo")
    applicationName = "amps-ha-demo"
}

tasks.named<JavaExec>("run") {
    // File-backed stores land under amps-ha-demo/data/, next to the compose
    // stack's deploy/ folder; both are git-ignored.
    workingDir = projectDir
    // -Dha.uris=... -Dha.count=... on the gradle command line reach the demo.
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("ha.") }
            .associateWith { System.getProperty(it) }
    )
}

/**
 * The integration suite compiles against main and the unit-test helpers, and
 * runs on its own task so a plain `build` never needs a container.
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
    // Testcontainers directly rather than :amps-test-harness: that harness
    // starts ONE instance of a flow under server/config/flows, and this suite
    // needs two instances on a shared network with fixed hostnames, config
    // from this module, and the ability to kill and revive either one.
    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(libs.awaitility)
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Kill one AMPS of a replicated pair under a publisher and a consumer, and prove nothing was lost."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // Same reasoning as the other modules: each variable is declared an INPUT,
    // not merely forwarded, because org.gradle.caching=true would otherwise
    // restore an all-skipped result for a run that has the image.
    listOf(
        "AMPS_IMAGE", "AMPS_BIN", "AMPS_PLATFORM",
        "DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED", "TESTCONTAINERS_HOST_OVERRIDE",
    ).forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // Where the two instance configs are, independent of the working directory.
    systemProperty("ha.configDir", layout.projectDirectory.dir("config").asFile.absolutePath)
    systemProperty("ha.workDir", layout.buildDirectory.dir("ha-it").get().asFile.absolutePath)

    workingDir = projectDir
    testLogging {
        showStandardStreams = true
    }
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}

/**
 * Parse both instance configs as XML on every build.
 *
 * The same check the server module runs on its flows: an XML comment may not
 * contain a double hyphen, and these files carry long explanatory comments.
 * AMPS rejects a malformed config at startup, which is a slow and confusing
 * way to find out; `scripts/ha-compose.sh validate` is the real check, with
 * the server's own parser, but it needs a container.
 */
val checkConfigXml = tasks.register("checkConfigXml") {
    group = "verification"
    description = "Verify both AMPS instance configs are well-formed XML."

    val configDir = layout.projectDirectory.dir("config").asFile
    inputs.dir(configDir)
    outputs.upToDateWhen { false }

    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        val problems = mutableListOf<String>()
        val files = listOf("primary", "secondary").map { File(configDir, "$it/amps-config.xml") }
        files.forEach { file ->
            if (!file.isFile) {
                problems += "missing: ${file.relativeTo(projectDir)}"
                return@forEach
            }
            try {
                factory.newDocumentBuilder().parse(file)
            } catch (e: Exception) {
                problems += "${file.relativeTo(projectDir)}: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("AMPS config problems:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("amps-ha-demo: ${files.size} instance configs are well-formed XML")
    }
}

/** The README carries relative links; one that points at nothing is rot. */
val checkModuleDocs = tasks.register("checkModuleDocs") {
    group = "verification"
    description = "Verify the relative links in this module's markdown resolve."

    val moduleDir = layout.projectDirectory.asFile
    inputs.file(layout.projectDirectory.file("README.md"))
    outputs.upToDateWhen { false }

    doLast {
        val linkPattern = Regex("""\[[^]]*]\((?!https?://|#)([^)#]+)(?:#[^)]*)?\)""")
        val document = File(moduleDir, "README.md")
        val problems = mutableListOf<String>()
        linkPattern.findAll(document.readText()).forEach { match ->
            val target = match.groupValues[1].trim()
            if (!File(document.parentFile, target).exists()) {
                problems += "${document.name}: broken link -> $target"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("documentation problems:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("amps-ha-demo: README links resolve")
    }
}

tasks.named("check") {
    dependsOn(checkConfigXml, checkModuleDocs)
}
