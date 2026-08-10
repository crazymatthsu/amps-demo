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
include("docs")
