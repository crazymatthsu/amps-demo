# amps-connectors/apps

Connector applications that need **code**, not just configuration.

Most connector applications are the generic runner
(`:amps-connectors:connector-app`) deployed with a different mounted
configuration — that is the whole point of the config tree under
`amps-connectors/config/`. A module belongs here only when the application adds
something the framework cannot express as YAML.

## What is code, and what is configuration

The line has moved since resources, rules, alerts and the control channel became
part of the framework, so it is worth being precise about which side of it a
requirement falls on:

| stays configuration (a directory under `config/`) | needs a module here |
|---|---|
| a new feed onto a new topic: `source:` + `amps:` | a decoder for a format nobody else speaks, or a `PayloadCodec` bean for a typed payload (Thrift, protobuf, a Hazelcast `IdentifiedDataSerializable`) named by its factory and class ids |
| dropping, renaming, mapping codes, computing a field: `transforms:` (`keep`, `drop`, `rename`, `set`, `values`, `derive`) | a `RecordTransform` bean named by a `transforms: [ { bean: ... } ]` step — an enrichment that *looks a record up* somewhere |
| conditional actions with counters and alerts: a `rules:` step (`when` → `set` / `bean` / `alert` / `drop`) | an `AppResource` bean for a client with no generic configuration (a KDB handle, a gRPC stub) |
| a reference table from a database: `resources: [ { name, jdbc: { ... } } ]` | a `CommandHandler` bean for a command of the application's own |
| alerts onto an AMPS and/or Kafka topic: `alerts:` | an `AlertSink` for a destination the framework does not ship |
| `reload` and `status` from an AMPS or Kafka topic: `control:` | a JDBC driver other than the blessed one |

A `rules` step that names a `bean` action still needs the bean's module; the rule
is configuration, the bean is code.

## The example: `instrument-enricher`

`amps-connectors/apps/instrument-enricher/` is the worked example, and the shape
every module here follows: a `@SpringBootApplication` main class, the app's own
`@ConfigurationProperties` (`enricher.*`), a `@Configuration` that declares one
`@Bean(name = "instrumentEnricher") RecordTransform` holding a `JdbcLookupTable`
taken from the `ResourceRegistry` at construction, and the transform itself — a
plain class, unit-tested against an H2-backed table with no Spring context. Its
deployable configuration is a directory like any other,
`amps-connectors/config/local/streams/instrument-enricher/application.yml`: the
`instruments` resource, a Kafka FIX connector whose transforms are
`drop` → `bean: instrumentEnricher` → `rules`, the control channel on
`connectors/control` and the alerts on `connectors/alerts`. The module's own
`README.md` walks through it; so does "Writing a code transform" in
[`../README.md`](../README.md).

## Adding one

Adding a module is creating a directory with a `build.gradle.kts` in it:

```
amps-connectors/apps/<name>/build.gradle.kts
amps-connectors/apps/<name>/src/main/java/...
```

The root `settings.gradle.kts` discovers every directory here that has a
`build.gradle.kts` and includes it as `:amps-connectors:apps:<name>`, so app #51
never means editing the settings file. The build file itself is short — the
`amps.connector-app` convention plugin carries the Boot BOM, the core dependency,
the actuator stack, the two test suites (with core's test fixtures, including the
shared `ConnectorAppRunner` for an integration test against a throwaway AMPS) and
the container image tasks:

```kotlin
plugins {
    id("amps.connector-app")
}

description = "..."

dependencies {
    // only the transports and resources this app actually dials
    implementation(project(":amps-connectors:source-kafka"))
    implementation(project(":amps-connectors:source-amps"))     // the control channel's listener
    implementation(project(":amps-connectors:resource-jdbc"))   // resources[].jdbc
}
```

The compose script runs a config directory whose name matches a module here as
`localhost/amps-<name>:local` instead of the generic image, so the example's
directory under `config/local/streams/` deploys the example's jar with nothing
further said.

One rule about the bean graph, because it is the only way to make the context
fail to start: a resource or an alert sink must not depend on `TransformRegistry`
or `ConnectorManager`. The transform registry instantiates every
`RecordTransform`, an enricher holds a resource, the resource registry is built
from the resources and the `AlertManager`, and the manager from the sinks — a
chain, as long as nothing at the far end reaches back. A resource that wants to
raise takes `Alerts`; a `CommandHandler` that needs the connectors is given them in
its `CommandContext` when a command arrives.
