// :amps-connectors:source-kafka -- the Kafka driver for the connector framework.
//
// One module per transport, so an application carries the clients it actually dials:
// kafka-clients lives HERE rather than in :amps-connectors:core, and a TCP-only or
// JDBC-only deployment never sees it. Core knows nothing about Kafka; this module
// contributes a KafkaSourceFactory through its own auto-configuration and the
// SourceResolver picks it up.
plugins {
    `java-library`
}

description = "Kafka RecordSource for amps-connectors: a consumer subscription -> SourceRecord"

dependencies {
    // Same Boot BOM as core, so a source module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

    // Version managed by the Boot BOM, which is what the rest of the repo's Kafka
    // consumers would be built against.
    implementation(libs.kafka.clients)

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
