// :amps-connectors:resource-jdbc -- a database table as an application resource.
//
// The connector framework's first resource module: a query loaded into memory as a
// lookup table, swapped atomically on every reload, for the code transforms that enrich
// one feed's records from another system's reference data. One module per resource kind
// for the same reason there is one per source transport -- an application carries only
// the clients it actually dials -- and this one depends on :amps-connectors:source-jdbc
// rather than on java.sql alone, because that module owns JdbcValues (the one rule for
// turning a result-set value into the framework's value, so a row published from a
// table and a row looked up in one agree) and ships the blessed PostgreSQL driver. Core
// knows nothing about JDBC; this module contributes a JdbcResourceFactory through its
// own auto-configuration and the ResourceRegistry picks it up.
plugins {
    `java-library`
}

description = "JDBC AppResource for amps-connectors: a query -> a reloadable in-memory lookup table"

dependencies {
    // Same Boot BOM as core, so a resource module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    // JdbcValues, and transitively the one blessed driver (runtimeOnly there, so still
    // runtime-only here): a config-only application that names a PostgreSQL URL under
    // resources[].jdbc reaches it without a rebuild.
    implementation(project(":amps-connectors:source-jdbc"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
    testImplementation(testFixtures(project(":amps-connectors:core")))
    // A real database for the tests, in-process: what a load keeps, what a reload picks
    // up and what a failed one leaves standing are all result-set behaviour, and none of
    // it is worth asserting against a mock.
    testImplementation(libs.h2)
    // The launcher must come from the same JUnit release as the engine Boot's BOM
    // pins; Gradle's own bundled one is older, and the mismatch fails discovery
    // outright ("OutputDirectoryProvider not available").
    testRuntimeOnly(libs.junit.platform.launcher)
}
