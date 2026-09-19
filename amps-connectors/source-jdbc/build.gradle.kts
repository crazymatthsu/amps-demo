// :amps-connectors:source-jdbc -- the database driver for the connector framework.
//
// One module per transport, so an application carries the clients it actually dials.
// java.sql itself is in the JDK, but a JDBC *driver* is not: this module ships the one
// blessed driver (PostgreSQL) so the generic runner image can reach a real database
// without a rebuild, and any other driver arrives with the custom app under apps/ that
// needs it. Core knows nothing about JDBC; this module contributes a JdbcSourceFactory
// through its own auto-configuration and the SourceResolver picks it up.
plugins {
    `java-library`
}

description = "JDBC RecordSource for amps-connectors: a polled query -> SourceRecord"

dependencies {
    // Same Boot BOM as core, so a source module never writes a Spring version either.
    implementation(platform(libs.spring.boot.bom))

    implementation(project(":amps-connectors:core"))
    implementation(libs.spring.boot.starter)
    implementation(libs.slf4j.api)

    // A result-set row has no wire format, so this source builds one: jackson writes each
    // row as the JSON object the connector's decoder then reads. Core keeps its own copy
    // `implementation`, so it does not reach this module's compile classpath.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // The one driver the generic runner image carries, so a config-only application can
    // dial a real database. runtimeOnly: nothing here compiles against it -- the source
    // speaks java.sql and DriverManager finds this on the classpath. Another database
    // means another module under apps/ with its own driver, not a second entry here.
    runtimeOnly(libs.postgresql)

    annotationProcessor(platform(libs.spring.boot.bom))
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.awaitility)
    testImplementation(testFixtures(project(":amps-connectors:core")))
    // A real database for the tests, in-process: DDL, DML and result-set typing are what
    // this source is made of, and none of it is worth asserting against a mock.
    testImplementation(libs.h2)
    // The launcher must come from the same JUnit release as the engine Boot's BOM
    // pins; Gradle's own bundled one is older, and the mismatch fails discovery
    // outright ("OutputDirectoryProvider not available").
    testRuntimeOnly(libs.junit.platform.launcher)
}
