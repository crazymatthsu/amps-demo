// :amps-connectors:source-tcp -- the raw-socket driver for the connector framework.
//
// The one source module with no client dependency at all: a framed TCP reader is
// java.net plus the framing rules in TcpSourceProperties. It still gets its own
// module rather than folding into core, because core knows nothing about transports
// -- this module contributes a TcpSourceFactory through its own auto-configuration
// and the SourceResolver picks it up.
plugins {
    `java-library`
}

description = "TCP RecordSource for amps-connectors: a framed socket stream -> SourceRecord"

dependencies {
    // Same Boot BOM as core, so a source module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

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
