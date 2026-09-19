// :amps-connectors:source-hazelcast -- the Hazelcast driver for the connector framework.
//
// One module per transport, so an application carries the clients it actually dials.
// Always the Hazelcast CLIENT, never an embedded member: a connector that joined the
// cluster would take a share of its partitions, so restarting the connector would
// migrate data. Core knows nothing about Hazelcast; this module contributes a
// HazelcastSourceFactory through its own auto-configuration and the SourceResolver
// picks it up.
plugins {
    `java-library`
}

description = "Hazelcast RecordSource for amps-connectors: a topic subscription -> SourceRecord"

dependencies {
    // Same Boot BOM as core, so a source module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

    // The same Hazelcast this repo already runs in hazelcast-persistent-store.
    implementation(libs.hazelcast)

    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
    testImplementation(testFixtures(project(":amps-connectors:core")))
    // The launcher must come from the same JUnit release as the engine Boot's BOM
    // pins; Gradle's own bundled one is older, and the mismatch fails discovery
    // outright ("OutputDirectoryProvider not available").
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Hazelcast's documented JVM flags for JDK 9+: without them a member still runs, but
// logs warnings and loses some optimizations. Needed here because the tests start a
// real embedded member for the client to talk to -- the same list
// hazelcast-persistent-store applies.
val hazelcastJvmArgs = listOf(
    "--add-modules", "java.se",
    "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.management/sun.management=ALL-UNNAMED",
    "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED"
)

tasks.test {
    jvmArgs(hazelcastJvmArgs)
    // A build is not a telemetry opportunity, and the call it makes at startup is time
    // the suite spends waiting. Set as a JVM property as well as on the member's Config,
    // so it also covers anything Hazelcast reads before that config is applied.
    systemProperty("hazelcast.phone.home.enabled", "false")
    // The member logs through this module's slf4j rather than Hazelcast's JUL default.
    systemProperty("hazelcast.logging.type", "slf4j")
}
