# amps-test-harness

Starts a throwaway AMPS instance in a container, for the integration suites of
[fix42-publisher](../fix42-publisher/README.md),
[cache-persistent-store](../cache-persistent-store/README.md) and
[hazelcast-persistent-store](../hazelcast-persistent-store/README.md).

## Why it exists

Those three modules each had their own copy of the same ~290-line harness.
Diffing them, the entire variation was four values — which flow to run, what to
call the container, whether the client URI selects `fix` or `json`, and the
package the copy happened to sit in. Three copies of one idea is three places
to fix the next port race. The four values are now [`AmpsFlow`](src/main/java/com/demo/amps/testharness/AmpsFlow.java)
and everything else is shared.

## Two backends, one switch

`AMPS_TEST_HARNESS` chooses. Both run the same tests behind the same
[`AmpsTestServer`](src/main/java/com/demo/amps/testharness/AmpsTestServer.java)
interface, so no test knows which one it got.

| value | backend | needs | good for |
| --- | --- | --- | --- |
| `cli` *(default)* — aliases `podman`, `docker` | `podman run` as a subprocess | the engine binary on `PATH` | a laptop: nothing to set up beyond `AMPS_IMAGE` |
| `testcontainers` — alias `tc` | the engine's Docker API | a Docker-API-compatible socket | CI: the socket is there by default, and the reaper cleans up after a killed JVM |

An unrecognised value throws rather than falling back — silently running the
other backend is the same class of mistake as a stale cached result: the build
looks like it did what you asked and did something else.

```bash
# default
AMPS_IMAGE=<image> ./gradlew integrationTest

# through the Docker API instead
AMPS_IMAGE=<image> AMPS_TEST_HARNESS=testcontainers ./gradlew integrationTest
```

With podman, the socket usually needs pointing at:

```bash
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
```

In CI also set `TESTCONTAINERS_RYUK_DISABLED=true`: the runner is ephemeral so
there is nothing to reap, and it avoids pulling `testcontainers/ryuk` from
Docker Hub, whose anonymous rate limits are shared across runner IPs.

## Using it

```java
Optional<String> unavailable = AmpsTestServer.unavailableReason(AmpsFlow.CACHE);
assumeTrue(unavailable.isEmpty(), () -> "skipping: " + unavailable.get());

try (AmpsTestServer server = AmpsTestServer.start(AmpsFlow.CACHE)) {
    Client client = new Client("it");
    client.connect(server.uri());
    ...
}
```

`unavailableReason` is returned rather than thrown so a suite can turn it into
a **skip** with a readable reason. That keeps `./gradlew build` green on a
machine that has never seen AMPS — and means a green build is not by itself
proof these ran. Check for `SKIPPED` if it matters.

Declare it from the consuming source set:

```kotlin
"integrationTestImplementation"(project(":amps-test-harness"))
```

Testcontainers is an `implementation` dependency here, not `api`: consumers
pick a backend with an environment variable, never by importing a type.

## Three things it gets right that are easy to get wrong

- **Readiness is the server's own `initialization completed` log line, not an
  open port.** The engine's port forwarder accepts connections as soon as the
  container exists, so a client that races it connects successfully and is then
  dropped mid-logon. `restart()` *counts* those markers rather than matching
  one, because the previous run's is still in the log afterwards — testing for
  presence returns instantly and hands back a server that is still starting.
- **State is fresh per run but survives `restart()`.** Both properties are
  needed at once: a run polluted by the last one proves nothing, and neither
  does a restart that quietly starts from empty. The CLI backend gets this from
  a per-instance `build/<shortName>-it/` directory; the Testcontainers backend
  from the container's own writable layer. A tmpfs would satisfy the first and
  silently break the second.
- **`AMPS_TEST_HARNESS` belongs in the Gradle build cache key**, alongside
  `AMPS_IMAGE`. A variable that is only forwarded is invisible to the key, and
  this repo sets `org.gradle.caching=true` — so a result from one backend would
  be restored for a run that asked for the other, reporting success without
  ever exercising it. Each consuming module declares them with
  `inputs.property`.

## Adding a flow

Add a constant to `AmpsFlow`, mirroring `server/config/flows/`:

```java
public static final AmpsFlow MY_FLOW = new AmpsFlow("my-flow", "myflow", "json");
```

The third value is the message type the client URI selects. Getting it wrong is
not a connection error — the client connects and then cannot parse anything.
