# amps-connectors/apps

Connector applications that need **code**, not just configuration.

Most connector applications are the generic runner
(`:amps-connectors:connector-app`) deployed with a different mounted
configuration — that is the whole point of the config tree under
`amps-connectors/config/`. A module belongs here only when the application adds
something the framework cannot express as YAML: a `RecordTransform` bean named by
a `transforms: [ { bean: ... } ]` step, a decoder for a format nobody else speaks,
a JDBC driver other than the blessed one.

Adding one is creating a directory with a `build.gradle.kts` in it:

```
amps-connectors/apps/<name>/build.gradle.kts
amps-connectors/apps/<name>/src/main/java/...
```

The root `settings.gradle.kts` discovers every directory here that has a
`build.gradle.kts` and includes it as `:amps-connectors:apps:<name>`, so app #51
never means editing the settings file. The build file itself is short — the
`amps.connector-app` convention plugin carries the Boot BOM, the core dependency,
the actuator stack, the two test suites and the container image tasks:

```kotlin
plugins {
    id("amps.connector-app")
}

description = "..."

dependencies {
    // only the transports this app actually dials
    implementation(project(":amps-connectors:source-kafka"))
}
```
