// :amps-connectors:connector-app -- the GENERIC runner.
//
// One image, deployed N times: every config-only connector application in
// amps-connectors/config/<env>/<flow>/<app-name>/ runs as this app under its own
// spring.application.name. An app that needs custom code gets its own module under
// apps/ instead; this build file is the template for what that costs.
plugins {
    id("amps.connector-app")
}

description = "Generic source -> AMPS connector application; connectors arrive as mounted configuration"

dependencies {
    // Every source module, because this is the ONE image the whole fleet deploys: an
    // instance picks its transport in configuration (source.tcp / source.kafka /
    // source.jdbc / source.hazelcast), and a driver missing from the image would turn
    // that into a startup failure on the day someone writes a different block. An app
    // under apps/ that only ever dials one broker can depend on just that module.
    implementation(project(":amps-connectors:source-tcp"))
    implementation(project(":amps-connectors:source-kafka"))
    implementation(project(":amps-connectors:source-jdbc"))
    implementation(project(":amps-connectors:source-hazelcast"))

    // The JDBC integration test polls a real in-process database rather than the
    // PostgreSQL the image ships: the source speaks java.sql and DriverManager, so H2
    // exercises the same DDL, result set, column labels and types with nothing to start.
    // runtimeOnly -- the test names no H2 class, only a jdbc:h2: URL.
    "integrationTestRuntimeOnly"(libs.h2)

    // HazelcastToAmpsIT starts a real embedded MEMBER in the test JVM for the connector's
    // client to talk to, and HazelcastSmoke drives a real client from the host. Hazelcast is
    // an `implementation` dependency of :source-hazelcast, so it reaches this module's
    // runtime but not its compile classpath -- the suites name IMap and HazelcastJsonValue,
    // so it has to be declared here too.
    "integrationTestImplementation"(libs.hazelcast)
}

/**
 * Hazelcast's documented JVM flags for JDK 9+. Without them a member still runs, but logs
 * warnings and loses some optimizations -- the same list :amps-connectors:source-hazelcast
 * and :hazelcast-persistent-store apply, needed here for the same reason: the integration
 * suite starts a member, and the smoke driver a client.
 */
val hazelcastJvmArgs = listOf(
    "--add-modules", "java.se",
    "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.management/sun.management=ALL-UNNAMED",
    "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED"
)

tasks.named<Test>("integrationTest") {
    jvmArgs(hazelcastJvmArgs)
    // A build is not a telemetry opportunity, and the call it makes at startup is time the
    // suite spends waiting. As a system property as well as on the member's Config, so it
    // also covers anything Hazelcast reads before that config is applied.
    systemProperty("hazelcast.phone.home.enabled", "false")
    // The member logs through this application's slf4j rather than Hazelcast's JUL default.
    systemProperty("hazelcast.logging.type", "slf4j")
}

/**
 * The JVM half of `amps-connectors/scripts/hazelcast-smoke.sh`: writing to the Hazelcast
 * container and reading the AMPS one back. The script owns the containers; this owns the two
 * protocols nothing on the host speaks.
 *
 *   ./gradlew :amps-connectors:connector-app:hazelcastSmoke \
 *       --args="feed --hazelcast localhost:25701 --amps localhost:29007"
 *
 * On the integrationTest runtime classpath because that is where both clients already are --
 * and where the driver lives, beside the suite that makes the same assertions in-process.
 */
tasks.register<JavaExec>("hazelcastSmoke") {
    group = "verification"
    description = "Feed the smoke stack's Hazelcast map, or verify the SOW it produced."
    mainClass.set("com.demo.amps.connectors.it.smoke.HazelcastSmoke")
    classpath = sourceSets["integrationTest"].runtimeClasspath
    jvmArgs(hazelcastJvmArgs)
    systemProperty("hazelcast.phone.home.enabled", "false")
    systemProperty("hazelcast.logging.type", "slf4j")
}

/**
 * The README carries relative links; a link that points at a file that does not exist is
 * the kind of rot the docs module already refuses. The document belongs to the whole
 * amps-connectors tree rather than to this module, so its links resolve against
 * amps-connectors/ -- which is also where a reader opens it from.
 */
val checkModuleDocs = tasks.register("checkModuleDocs") {
    group = "verification"
    description = "Verify the relative links in amps-connectors/README.md resolve."

    val moduleDir = layout.projectDirectory.dir("..").asFile
    inputs.file(layout.projectDirectory.file("../README.md"))
    outputs.upToDateWhen { false }

    doLast {
        val linkPattern = Regex("""\[[^]]*]\((?!https?://|#)([^)#]+)(?:#[^)]*)?\)""")
        val document = File(moduleDir, "README.md")
        val problems = mutableListOf<String>()
        linkPattern.findAll(document.readText()).forEach { match ->
            val target = match.groupValues[1].trim()
            if (!File(document.parentFile, target).exists()) {
                problems += "${document.name}: broken link -> $target"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("documentation problems:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle("amps-connectors: README links resolve")
    }
}

tasks.named("check") {
    dependsOn(checkModuleDocs)
}
