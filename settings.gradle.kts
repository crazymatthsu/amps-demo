pluginManagement {
    // Convention plugins for the connector applications (amps.connector-app).
    includeBuild("build-logic")
    repositories {
        // Maven Central first: the protobuf plugin is published there too, so the
        // build works in networks that only allow repo1.maven.org.
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "amps-demo"

include("common")
include("server")
include("clients")
include("utils")
include("docs")
include("amps-test-harness")
include("fix42-publisher")
include("amps-cli")
include("amps-quickfixj")
include("cache-persistent-store")
include("hazelcast-persistent-store")

// A FIX publisher that recovers its sender sequence number (tag 8888) from
// AMPS after a disconnect; see fix-pub-seqno/README.md.
include("fix-pub-seqno")

// A QuickFIX/J 2.x drop-copy FIX engine on Spring Boot + Spring Integration,
// whose session sequence numbers are replicated to AMPS so a DR instance can
// take over without a resequence; see quickfixj-v2-demo/README.md.
include("quickfixj-v2-demo")

// amps-connectors is framework + drivers + applications: the core library (the
// decode -> filter -> transform -> key -> encode -> batch -> publish pipeline and
// the source SPI), one module per source transport, the generic runner every
// config-only connector application deploys as, and auto-discovered custom apps.
// Adding custom app #51 means creating amps-connectors/apps/<name>/build.gradle.kts
// -- not editing this file; see amps-connectors/apps/README.md.
include("amps-connectors:core")
include("amps-connectors:source-tcp")
include("amps-connectors:source-kafka")
include("amps-connectors:source-jdbc")
include("amps-connectors:source-hazelcast")
include("amps-connectors:connector-app")
file("amps-connectors/apps").listFiles()
    ?.filter { it.isDirectory && File(it, "build.gradle.kts").exists() }
    ?.sortedBy { it.name }
    ?.forEach { include(":amps-connectors:apps:${it.name}") }
