/**
 * quickfixj-v2-demo: a QuickFIX/J 2.x drop-copy FIX engine on Spring Boot,
 * with Spring Integration carrying each received message through a list of
 * configurable enrichment rules to AMPS topics and/or other FIX sessions --
 * and a QuickFIX/J MessageStore whose sequence numbers are written to the
 * usual file store AND replicated to AMPS by a write-behind thread, so a DR
 * instance can start on an empty disk and resume the session where the
 * primary left it.
 *
 *   ./gradlew :quickfixj-v2-demo:bootRun -Prole=venue      # acceptor + mock feed
 *   ./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy   # initiator -> rules -> AMPS
 *
 * Two test suites, split the same way as fix42-publisher's:
 *
 *   test              unit tests: the store, the write-behind publisher, the
 *                     recovery decision, the rules and destinations, the
 *                     config binding, and an in-process acceptor/initiator
 *                     pair driving the whole pipeline. No AMPS.
 *   integrationTest   the failover itself against a throwaway AMPS container:
 *                     stop the consumer, delete its file store, start it
 *                     again, watch it recover its numbers from AMPS and log
 *                     on without a resend. Skipped when AMPS_IMAGE is unset.
 *
 * The bootJar IS built here, unlike fix42-publisher: it is what the
 * Containerfile copies into the image the compose stack runs.
 */
plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.integration)
    implementation(libs.quickfixj.core)
    implementation(libs.quickfixj.messages.fix42)
    implementation(libs.amps.client)
    // The sequence-number checkpoint is a small JSON record; Gson is what the
    // other modules already parse JSON with, and it keeps the store library
    // free of the Spring web stack.
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
}

/**
 * The integration suite compiles against the main source set and the unit-test
 * helpers (the in-process engine harness lives there), and runs on its own
 * task so a plain `build` never needs a container.
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
    description = "Fail a FIX engine over onto an empty disk and recover its sequence numbers from a real AMPS."
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

    // The container harness finds server/config/flows relative to the
    // repository root; the tests find this module's dictionary from there too.
    workingDir = rootProject.projectDir
    testLogging {
        showStandardStreams = true
    }
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}

// The unit tests start real QuickFIX/J sessions over loopback and write file
// stores; both land under build/, never in the module's own data/ folder.
tasks.named<Test>("test") {
    workingDir = projectDir
    systemProperty("qfj2.test.workdir", layout.buildDirectory.dir("test-work").get().asFile.absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // A stable name, because the Containerfile COPYs it by name.
    archiveFileName.set("quickfixj-v2-demo.jar")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Config paths in the YAML and the QuickFIX/J settings are relative to the
    // module directory (config/, data/, log/) -- the same layout the container
    // has under /app, so one set of files serves both.
    workingDir = projectDir
    // -Prole=venue|dropcopy layers config/<role>/<role>.yml over the
    // defaults; the same thing the compose file does with a command argument.
    // A system property rather than a program argument, because Gradle's
    // --args REPLACES the configured argument list, and
    //   bootRun -Prole=dropcopy --args="--spring.profiles.active=seqno-admin ..."
    // is exactly how the admin tool is meant to be run.
    providers.gradleProperty("role").orNull?.let { role ->
        systemProperty("spring.config.additional-location", "file:config/$role/$role.yml")
    }
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("qfj.") || it.startsWith("seqno.") || it.startsWith("spring.") }
            .associateWith { System.getProperty(it) }
    )
}

/**
 * The README carries relative links; a link that points at a file that does
 * not exist is the kind of rot the docs module already refuses.
 */
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
        logger.lifecycle("quickfixj-v2-demo: README links resolve")
    }
}

tasks.named("check") {
    dependsOn(checkModuleDocs)
}
