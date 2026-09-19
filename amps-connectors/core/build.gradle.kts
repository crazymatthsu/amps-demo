// :amps-connectors:core -- the source -> AMPS pipeline as a LIBRARY.
//
// Deliberately NOT a Spring Boot application: applications depend on this project,
// and depending on an executable Boot module drags in someone else's main class and
// its baked application.yml. Core contributes its beans through
// ConnectorsAutoConfiguration instead, and ships its shared test doubles
// (FakeRecordSource, FakeSourceFactory, TestConnectors) as test fixtures so the
// source modules and the applications test against the same ones.
//
// No SOURCE transport client is a dependency here: kafka-clients, the JDBC drivers
// and the Hazelcast client each live in their own :amps-connectors:source-* module
// and reach core through the SourceFactory SPI, so core compiles and tests against
// no broker at all. The AMPS client is the one exception and not an exception to
// the rule: it is the TARGET every connector publishes into, not one of the sources
// they read, which is why it is `api` rather than `implementation`.
plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Source -> AMPS connector framework: decode, filter, transform, key, encode, batch, publish"

dependencies {
    // The Boot BOM as an api platform: consumers inherit the same Spring versions
    // the framework compiled against, and never write one themselves.
    api(platform(libs.spring.boot.bom))

    implementation(libs.spring.boot.starter)
    // Spring Integration carries the pipeline: one channel and one aggregator per
    // connector, so batching by size and by idle time is configuration, not a timer
    // this module had to write.
    implementation(libs.spring.boot.starter.integration)
    // `api`, not `implementation`: the annotations are on the config classes' fields, which
    // ARE this module's public API, so a source module compiling against ConnectorProperties
    // reads @Min and @NotBlank out of the class file. Without them on its compile classpath
    // javac warns once per annotation ("Cannot find annotation method 'value()' in type
    // 'Min'"), and this repo compiles everything with -Xlint:all.
    api(libs.spring.boot.starter.validation)
    // jackson, for the JSON decoder and encoder (no web server is started)
    implementation(libs.spring.boot.starter.json)

    // The publish side of every connector; part of this module's contract.
    api(libs.amps.client)
    implementation(libs.slf4j.api)

    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
    // Gradle 8.14 bundles an older platform launcher than the engine the Boot BOM pins, and
    // the mismatch surfaces as "OutputDirectoryProvider not available" before a single test
    // runs. Every other module in this repo declares it for the same reason.
    testRuntimeOnly(libs.junit.platform.launcher)

    // The fixtures build connector configurations and stand in for a publisher, so
    // they need the same BOM and the AMPS types on their own compile classpath.
    testFixturesImplementation(platform(libs.spring.boot.bom))
    testFixturesApi(libs.amps.client)
    testFixturesImplementation(libs.awaitility)
}
