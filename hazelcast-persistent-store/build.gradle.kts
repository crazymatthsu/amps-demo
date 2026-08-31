/**
 * hazelcast-persistent-store: Hazelcast open source persisting its IMaps in
 * AMPS, through the MapStore SPI.
 *
 * Hazelcast OSS has no native Persistence (hot-restart is Enterprise-only);
 * MapStore/MapLoader IS its sanctioned persistence mechanism. This module
 * implements that SPI on top of the tier-topic layout analyzed in TODO.md:
 * one composite-key SOW topic per persistence POLICY, shared by any number of
 * maps, so 50 caches need 2-3 topics rather than 50.
 *
 * Builds on cache-persistent-store's plumbing (SowOps, AmpsFilters,
 * JsonValues) rather than re-implementing it.
 *
 * Same test split as the sibling modules:
 *
 *   test              unit tests: the adapter against an in-memory tier
 *                     store, filter chunking, codec round trips. No AMPS, no
 *                     running Hazelcast member.
 *   integrationTest   AMPS in a container on the `hazelcast` flow plus real
 *                     embedded Hazelcast members: write through IMap, query
 *                     the SOW back, restart members and the server. Skipped
 *                     automatically when no image is available.
 */
plugins {
    `java-library`
    application
}

dependencies {
    // The adapter implements com.hazelcast.map.MapStore and the factory SPI:
    // Hazelcast is part of this module's contract.
    api(libs.hazelcast)
    // AmpsTierStore takes an AMPS Client in its constructor.
    api(libs.amps.client)
    implementation(project(":cache-persistent-store"))
    implementation(project(":common"))
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.demo.amps.hazelcast.HazelcastCacheDemo")
    applicationName = "hazelcast-cache-demo"
}

// Hazelcast's documented JVM flags for JDK 9+: without them the member still
// runs, but logs warnings and loses some optimizations. Applied to every task
// that actually starts a member (run, integrationTest) -- unit tests don't.
val hazelcastJvmArgs = listOf(
    "--add-modules", "java.se",
    "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.management/sun.management=ALL-UNNAMED",
    "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED"
)

tasks.named<JavaExec>("run") {
    jvmArgs(hazelcastJvmArgs)
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("amps.") }
            .associateWith { System.getProperty(it) }
    )
    workingDir = rootProject.projectDir
}

/**
 * The integration suite compiles against the main source set but runs on its
 * own task, so a plain `build` never needs a container.
 */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

// Declared here rather than in `dependencies` above: the configuration these
// name is created by the sourceSets block, which runs after it.
dependencies {
    // The container harness, shared with the other modules that need one.
    // Testcontainers arrives (or does not) as its implementation detail --
    // nothing here names a Testcontainers type.
    "integrationTestImplementation"(project(":amps-test-harness"))
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Persist real Hazelcast IMaps into a real AMPS instance and read both back."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
    jvmArgs(hazelcastJvmArgs)

    // The harness starts its own container, so it needs to know which image to
    // use, which engine, and -- since AMPS_TEST_HARNESS chooses between the CLI
    // and Testcontainers backends -- which of the two to start it with.
    //
    // Each is declared an INPUT, not merely forwarded. This repo sets
    // org.gradle.caching=true, and an environment variable that is only
    // forwarded is invisible to the cache key, so a run without AMPS_IMAGE
    // caches an all-skipped result and a later run WITH it restores that entry
    // instead of executing: FROM-CACHE, BUILD SUCCESSFUL, every test silently
    // skipped. AMPS_TEST_HARNESS matters most of all -- it does not merely
    // enable the suite, it changes which backend runs it.
    listOf(
        "AMPS_IMAGE", "AMPS_BIN", "AMPS_TEST_HARNESS",
        // The CLI harness.
        "CONTAINER_ENGINE", "AMPS_PLATFORM",
        // The Testcontainers harness.
        "DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED",
    ).forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // The flow config and the per-run data directory are found relative to
    // the repository root.
    workingDir = rootProject.projectDir
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}
