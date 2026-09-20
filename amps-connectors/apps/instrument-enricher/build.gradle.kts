// :amps-connectors:apps:instrument-enricher -- the example of a connector application
// that needs CODE.
//
// Everything the generic runner does, this does too; what it adds is one RecordTransform
// bean (`instrumentEnricher`) that looks a FIX symbol up in a JDBC reference table and
// writes the SEDOL and the currency into the record. That is the whole reason it is a
// module under apps/ rather than a directory under config/: a `bean:` step names code,
// and the code has to be on the classpath of the image that runs the configuration.
// Discovered by the root settings.gradle.kts, built as localhost/amps-instrument-enricher
// by the compose script, configured by config/local/streams/instrument-enricher/.
plugins {
    id("amps.connector-app")
}

description = "Example connector application: Kafka FIX orders enriched with SEDOL and currency " +
        "from a JDBC instrument table, published to AMPS"

/**
 * The driver a developer run may use in place of PostgreSQL. A configuration of its own,
 * added to the bootRun classpath below and nowhere else: `runtimeOnly` would bake H2 into
 * the deployable jar for the sake of a demo, and a reference-data table in a throwaway
 * in-memory database is exactly the thing an image must not be able to do by accident.
 */
val localDatabase: Configuration by configurations.creating

dependencies {
    // Only the transports this application dials, and the resource kind it enriches
    // from. The convention plugin supplies core, the actuator stack and the test suites.
    implementation(project(":amps-connectors:source-kafka"))    // the orders feed (and a Kafka
                                                                // control topic or alert sink)
    implementation(project(":amps-connectors:source-amps"))     // the control channel's
                                                                // subscription
    implementation(project(":amps-connectors:resource-jdbc"))   // the `instruments` table

    // A real in-process database for both suites: the unit tests seed an H2 table from
    // sql/instruments.sql and enrich against a real JdbcLookupTable, and the integration
    // test does the same beside a real AMPS. Test-only on purpose -- the image carries
    // the PostgreSQL driver that resource-jdbc ships, and H2 stays a development database.
    testImplementation(libs.h2)
    "integrationTestRuntimeOnly"(libs.h2)
    // The launcher must come from the same JUnit release as the engine Boot's BOM pins;
    // Gradle's own bundled one is older, and the mismatch fails discovery outright
    // ("OutputDirectoryProvider not available").
    testRuntimeOnly(libs.junit.platform.launcher)

    // H2 for `bootRun` ONLY, so the README's "run it against H2 in memory" recipe works
    // from the command line without the driver ever reaching bootJar or the image. The
    // BOM comes along because that is where H2's version lives.
    localDatabase(platform(libs.spring.boot.bom))
    localDatabase(libs.h2)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    classpath += localDatabase
}
