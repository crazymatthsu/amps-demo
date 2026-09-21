// :amps-connectors:source-amps -- the AMPS driver for the connector framework.
//
// One module per transport, even for the transport the framework already speaks: the
// AMPS client is core's TARGET dependency, but reading a topic is a different job with
// its own subscription modes, its own bookmark semantics and its own reconnect story,
// and keeping it a driver like the other four means an application that never reads
// AMPS never carries a SourceFactory for it. Core knows nothing about subscribing;
// this module contributes an AmpsSourceFactory through its own auto-configuration and
// the SourceResolver picks it up -- for a topic-to-topic bridge, or for the control
// channel, which is just another subscription.
plugins {
    `java-library`
}

description = "AMPS RecordSource for amps-connectors: a subscription -> InboundRecord"

dependencies {
    // Same Boot BOM as core, so a source module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

    // The client arrives through core's `api` dependency; named here as well because this
    // module compiles against it directly (HAClient, Command, Message), not only through
    // core's types.
    implementation(libs.amps.client)

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
