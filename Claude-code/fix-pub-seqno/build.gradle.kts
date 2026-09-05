/**
 * fix-pub-seqno: a FIX publisher that recovers from a disconnect by asking
 * AMPS for the last sender sequence number (tag 8888) it recorded, then
 * republishing the gap from its own outbox with no loss and no duplicate.
 * A bookmark-store subscriber checks the result end to end.
 *
 * Runs against the `fix-pub-seqno` server flow:
 *
 *   AMPS_FLOW=fix-pub-seqno ./server/scripts/amps.sh start
 *   ./gradlew :Claude-code:fix-pub-seqno:run --args="all"
 *
 * Two test suites, split the same way as fix42-publisher's:
 *
 *   test              unit tests: the outbox, the gap decision, the FIX codec,
 *                     the subscriber's sequence check. No AMPS, runs in `build`.
 *   integrationTest   the real thing against a throwaway AMPS container.
 *                     Skipped automatically when AMPS_IMAGE is unset, so a
 *                     plain `build` stays green on a machine without one.
 */
plugins {
    application
}

dependencies {
    // DemoConfig (where the instance is), Console, MessageStreams, and the
    // AMPS client itself, which common exposes as an api dependency.
    implementation(project(":common"))
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.demo.amps.seqno.SeqnoDemo")
    applicationName = "fix-pub-seqno"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    // The demo reads AMPS_* / -Damps.* settings; forward anything the user set.
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("amps.") || it.startsWith("seqno.") }
            .associateWith { System.getProperty(it) }
    )
    // Client-side state (outbox, bookmark store, high-water marks) lives under
    // build/client-state at the repository root, like the other demos.
    workingDir = rootProject.projectDir
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
    "integrationTestImplementation"(project(":amps-test-harness"))
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Crash, recover and verify against a real AMPS instance."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // Same reasoning as fix42-publisher: each variable is declared an INPUT,
    // not merely forwarded, because org.gradle.caching=true would otherwise
    // restore an all-skipped result for a run that has the image.
    listOf(
        "AMPS_IMAGE", "AMPS_BIN", "AMPS_TEST_HARNESS",
        "CONTAINER_ENGINE", "AMPS_PLATFORM",
        "DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED",
    ).forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // Config and flow files are found relative to the repository root.
    workingDir = rootProject.projectDir
    testLogging {
        showStandardStreams = true
    }
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}

/**
 * The design notes carry relative links; a link that points at a file that
 * does not exist is the kind of rot the docs module already refuses. Same
 * check here, for this module's own markdown.
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
        logger.lifecycle("fix-pub-seqno: ${documents.size} documents, links resolve")
    }
}

tasks.named("check") {
    dependsOn(checkModuleDocs)
}
