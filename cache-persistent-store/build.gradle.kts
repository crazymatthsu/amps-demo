/**
 * cache-persistent-store: a local java.util.Map cache with AMPS as its remote,
 * distributed persistent store.
 *
 * The library half is the MapLoader/MapStore SPI and two Map implementations
 * (flat and map-of-maps) that hydrate from an AMPS SOW at startup, write
 * through on mutation, and read through on a local miss. The AMPS half is one
 * store implementation per shape, speaking JSON against the SOW topics the
 * `cache` server flow declares (server/config/flows/cache/amps-config.xml).
 *
 * Two test suites, same split as fix42-publisher:
 *
 *   test              unit tests against in-memory stores. Cache semantics,
 *                     JSON round-trips, filter quoting. No AMPS, runs in
 *                     `build`.
 *   integrationTest   the real thing: starts an AMPS container on the `cache`
 *                     flow, writes through the cache, queries the SOW back,
 *                     and restarts both the "process" (a fresh cache instance)
 *                     and the server. Skipped automatically when no image is
 *                     available.
 */
plugins {
    `java-library`
    application
}

dependencies {
    // The stores take an AMPS Client in their constructors, so the client
    // library is part of this module's contract, not an implementation detail.
    api(libs.amps.client)
    // AmpsConnections/DemoConfig/MessageStreams for the demo entry point.
    implementation(project(":common"))
    // The cache holds arbitrary JSON-representable values, not protobuf
    // messages, so Gson is the codec here rather than common's JsonCodec.
    implementation(libs.gson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.demo.amps.cache.CacheDemo")
    applicationName = "cache-demo"
}

tasks.named<JavaExec>("run") {
    // Same convention as the other runnable modules: -Damps.* flags pass
    // through, and relative paths resolve against the repository root.
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

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Run the cache against a real AMPS instance and read the SOW back."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }

    // The harness starts its own container, so it needs to know which image
    // and engine to use. Forwarded rather than hard-coded: there is no public
    // AMPS image, so the value is necessarily site-specific.
    listOf("AMPS_IMAGE", "CONTAINER_ENGINE", "AMPS_PLATFORM", "AMPS_BIN").forEach { name ->
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

// `check` runs it, but the test itself opts out when no AMPS image is
// configured -- so this stays green on a laptop without one, and does real
// work on a machine that has one.
tasks.named("check") {
    dependsOn(integrationTestTask)
}
