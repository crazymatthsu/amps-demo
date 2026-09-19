plugins {
    `kotlin-dsl`
}

// The same toolchain the root build gives every Java subproject. Without it
// `kotlin-dsl` compiles against whatever JDK runs Gradle, and on a newer one that
// Kotlin has no target for it says so -- on EVERY invocation of the whole build,
// because this is an included build of the root settings.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Putting the Boot plugin on build-logic's classpath is what lets
    // amps.connector-app apply it and reference SpringBootPlugin.BOM_COORDINATES
    // directly. Resolved from the catalog's `spring-boot` plugin alias rather than
    // written out, so the version still lives in gradle/libs.versions.toml and
    // nowhere else: a plugin alias resolves to its MARKER artifact
    // (<id>:<id>.gradle.plugin:<version>), which is what a plain `implementation`
    // dependency needs to name.
    implementation(libs.plugins.spring.boot.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" })
}
