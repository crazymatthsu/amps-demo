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

// Demo modules developed in Claude Code sessions live under Claude-code/;
// each is an ordinary subproject with the folder as its parent path.
include("Claude-code:fix-pub-seqno")
