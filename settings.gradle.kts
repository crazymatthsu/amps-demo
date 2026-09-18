pluginManagement {
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
