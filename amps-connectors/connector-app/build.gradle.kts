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
