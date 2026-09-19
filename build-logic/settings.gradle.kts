// build-logic is an INCLUDED BUILD (wired in via pluginManagement in the root
// settings.gradle.kts), not buildSrc: buildSrc invalidates the whole build's
// configuration on any change, an included build is compiled and cached like any
// other project. It exists so that a connector application's build file stays a
// few lines however many applications there are.

dependencyResolutionManagement {
    repositories {
        // Maven Central first, for the same reason the root build gives: the
        // Boot plugin's marker is published there too, so this works in networks
        // that only allow repo1.maven.org.
        mavenCentral()
        gradlePluginPortal()
    }

    // The SAME catalog the main build reads, so a version written in
    // gradle/libs.versions.toml is the only place it is written -- including the
    // Boot plugin version the convention plugin puts on its own classpath.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
