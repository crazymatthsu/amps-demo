// The plugin every connector APPLICATION applies (core does not: it is a plain
// java-library). This is why an app's build file is ~5 lines, and why 50 of them
// stay cheap: the Boot BOM, the core dependency, the actuator stack, the two test
// suites and the container image tasks all live here once.
//
// Source modules (:amps-connectors:source-tcp and friends) are NOT added here: an
// application declares the transports it dials, and the generic runner declares all
// of them.
//
// Deliberately absent: group, version, repositories and the Java toolchain. The
// root build.gradle.kts already applies all four to every Java subproject, and a
// second opinion here would be a second place to change them.

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot")
}

// A precompiled script plugin gets no generated `libs` accessor, so the one
// library the Boot BOM does not manage is looked up by name instead.
val catalog = the<VersionCatalogsExtension>().named("libs")

dependencies {
    // The Boot BOM as a platform: connector apps never write a Spring version anywhere.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // The whole pipeline arrives as a library + auto-configuration; an app is a main
    // class and configuration.
    implementation(project(":amps-connectors:core"))

    // Actuator (and the web server it needs) is not optional: the container
    // HEALTHCHECK in amps-connectors/docker/spring-boot.Containerfile and the compose
    // readiness probes depend on /actuator/health existing in every app.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // @Validated @ConfigurationProperties silently SKIPS @NotBlank without a
    // validator on the classpath; mandate it here rather than trusting 50 apps.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // An app's own @ConfigurationProperties get IDE metadata for free.
    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // FakeRecordSource, FakeSourceFactory, TestConnectors -- the doubles every app
    // test binds its configuration against.
    testImplementation(testFixtures(project(":amps-connectors:core")))
    // Asynchronous by construction: a connector publishes from a source thread and
    // releases batches from a scheduler, so app tests poll rather than sleep.
    testImplementation(catalog.findLibrary("awaitility").orElseThrow())
}

/**
 * The integration suite compiles against the main source set and the unit-test
 * helpers, and runs on its own task so a plain `build` never needs a container --
 * the same split every other module in this repo uses.
 */
val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations["integrationTestImplementation"]
    .extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"]
    .extendsFrom(configurations.testRuntimeOnly.get())

// Declared here rather than in `dependencies` above: the configuration this names
// is created by the sourceSets block, which runs after it.
dependencies {
    // The container harness, shared with the other modules that need one.
    // Testcontainers arrives (or does not) as its implementation detail --
    // nothing here names a Testcontainers type.
    "integrationTestImplementation"(project(":amps-test-harness"))
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Run this connector application against a real AMPS instance and read the SOW back."
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)

    // Same reasoning as fix42-publisher and quickfixj-v2-demo: each variable is
    // declared an INPUT, not merely forwarded, because org.gradle.caching=true
    // would otherwise restore an all-skipped result for a run that has the image.
    // AMPS_TEST_HARNESS matters most -- it does not merely enable the suite, it
    // chooses which backend runs it.
    listOf(
        "AMPS_IMAGE", "AMPS_BIN", "AMPS_TEST_HARNESS",
        // The CLI harness.
        "CONTAINER_ENGINE", "AMPS_PLATFORM",
        // The Testcontainers harness.
        "DOCKER_HOST", "TESTCONTAINERS_RYUK_DISABLED",
    ).forEach { name ->
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        if (value.isPresent) {
            environment(name, value.get())
        }
    }

    // The container harness finds server/config/flows relative to the repository
    // root, and the config tree the tests bind lives there too.
    workingDir = rootProject.projectDir
    testLogging {
        showStandardStreams = true
    }
}

// `check` runs it, but the suite itself opts out when no AMPS image is configured,
// so this stays green on a laptop without one.
tasks.named("check") {
    dependsOn(integrationTestTask)
}

tasks.named<BootRun>("bootRun") {
    // The config tree (amps-connectors/config/<env>/...) and the server flow files
    // are addressed from the repository root, the same way the container addresses
    // them from /app.
    workingDir = rootProject.projectDir
    // Point a connector somewhere else without editing YAML:
    //   ./gradlew :amps-connectors:connector-app:bootRun \
    //       -Damps-connectors.amps.host=amps-1 -Dspring.profiles.active=demo
    systemProperties(
        System.getProperties()
            .stringPropertyNames()
            .filter { it.startsWith("amps-connectors.") || it.startsWith("spring.") }
            .associateWith { System.getProperty(it) }
    )
}

tasks.named<BootJar>("bootJar") {
    // A stable name, because the staged docker context COPYs it by name.
    archiveFileName.set("${project.name}.jar")
}

// ---- container image ---------------------------------------------------------
// ONE shared Containerfile serves every app because the staged build context is
// generic: exactly `application.jar` + `Containerfile` in build/docker. There is no
// per-app Containerfile to drift, and `podman build build/docker` is the whole story.

val stageDockerContext by tasks.registering(Sync::class) {
    group = "docker"
    description = "Stages application.jar + the shared Containerfile into build/docker."
    into(layout.buildDirectory.dir("docker"))
    from(tasks.named("bootJar")) {
        rename { "application.jar" }
    }
    from(rootProject.layout.projectDirectory.file("amps-connectors/docker/spring-boot.Containerfile")) {
        rename { "Containerfile" }
    }
}

tasks.register<Exec>("dockerBuildLocal") {
    group = "docker"
    description = "Builds this app's image into podman as localhost/amps-<app>:local."
    dependsOn(stageDockerContext)
    workingDir = layout.buildDirectory.dir("docker").get().asFile
    // An Exec task resolves its binary against the GRADLE DAEMON's PATH, not the shell's,
    // and a daemon started from a launcher with a minimal PATH cannot find a podman
    // installed under /opt/podman/bin -- "A problem occurred starting process 'command
    // 'podman''", from a shell where podman works perfectly. CONTAINER_ENGINE (the same
    // variable amps-test-harness reads) names it; an absolute path always works.
    val engine = providers.environmentVariable("CONTAINER_ENGINE").orElse("podman")
    // --format docker keeps the Containerfile HEALTHCHECK; the default OCI format
    // drops it.
    commandLine(
        engine.get(), "build", "--format", "docker",
        "-t", "localhost/amps-${project.name}:local", "."
    )
}
