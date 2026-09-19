// :amps-connectors:source-hazelcast -- the Hazelcast driver for the connector framework.
//
// Two structures behind one driver: a topic (a stream of payloads) or an IMap (keyed state
// whose entries can also stop existing). One module per transport, so an application carries
// the clients it actually dials.
// Always the Hazelcast CLIENT, never an embedded member: a connector that joined the
// cluster would take a share of its partitions, so restarting the connector would
// migrate data. Core knows nothing about Hazelcast; this module contributes a
// HazelcastSourceFactory through its own auto-configuration and the SourceResolver
// picks it up.
plugins {
    `java-library`
}

description = "Hazelcast RecordSource for amps-connectors: a topic or map subscription -> SourceRecord"

dependencies {
    // Same Boot BOM as core, so a source module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

    // The same Hazelcast this repo already runs in hazelcast-persistent-store.
    implementation(libs.hazelcast)

    // A map value is whatever the cluster stores -- a Map, a List, a POJO -- and the pipeline
    // decodes a payload string. Gson renders the ones that are not already text as JSON, which
    // is the format a map connector reads. Same version the rest of the repo serialises with.
    implementation(libs.gson)

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

// Hazelcast annotates its event classes with SpotBugs' @SuppressFBWarnings and declares
// spotbugs-annotations as an optional dependency, so it is not on any consumer's compile
// classpath. Referencing EntryEvent therefore makes javac warn, once per use site, that it
// cannot read an annotation in someone else's class file -- a fact about Hazelcast's
// packaging and not about this module's code. The rest of -Xlint:all stays on; the
// alternative is carrying a SpotBugs dependency to silence a warning about a jar we only
// read.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-classfile")
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
