# `instrument-enricher`

The example of a connector application that needs **code**: FIX orders from a Kafka topic,
enriched with the instrument's SEDOL and currency from a database table, published onto a
server-keyed AMPS SOW topic. Everything the generic runner does, this does too; what it adds
is one `RecordTransform` bean, a shared resource for it to read, and the two channels a
running application needs beyond a log — a control topic to take commands from and an alerts
topic to report on. The framework these are features of is described in
[`amps-connectors/README.md`](../../README.md); this file is the walk-through of one
application built on it.

```
Kafka orders.fix (FIX)                                    AMPS sow/connectors/orders (fix, Key /11)
   35=D|11=ORD-7|55=K-0|38=100|40=2|44=10.5|10=123           35=D|11=ORD-7|55=K-0|38=100|40=2|44=10.5|48=B0YQ5W0|15=GBP|22=2
        │                                                                   ▲
        ▼                                                                   │
   filter 35 in [D,G,F]  →  drop [10]  →  bean: instrumentEnricher  →  rules  →  key /11  →  fix
                                                │
                                   resource `instruments` (JdbcLookupTable):
                                   SELECT symbol, sedol, isin, currency, ric FROM instruments
                                   reloaded every 5m, and on {"command":"reload"}
```

## What it does

`InstrumentEnricher` is a `RecordTransform`: for each record it reads the symbol
(`enricher.symbol-tag`, tag 55), looks it up in the `instruments` resource, and on a hit
writes every entry of `enricher.set` (a FIX tag taking a column of the row: `48` ← `sedol`,
`15` ← `currency`) and every entry of `enricher.literals` (`22` ← `2`, SecurityIDSource 2 =
SEDOL) into a **copy** of the field map — taken through `Fields.copy`, so a typed record's
`FieldView` is cloned rather than flattened into a plain map. A miss is counted, raised as
an `UNKNOWN_SYMBOL` alert, and then follows `enricher.on-miss`: `PASS` (the default)
publishes the order as it came, `DROP` discards it. A `DELETE` record and a record with no
symbol are not the transform's business and pass through untouched.

The table is a `JdbcLookupTable` — the `resources[].jdbc` kind from
`:amps-connectors:resource-jdbc` — held in memory as one immutable snapshot per load, swapped
atomically, so a lookup on a source's reader thread never waits for a load and never sees a
half-loaded table. A failed reload keeps the last good copy and says so
(`RESOURCE_RELOAD_FAILED`); a table that never loaded makes every record a
`RESOURCE_UNAVAILABLE` rather than an `UNKNOWN_SYMBOL`, because "the database is down" and
"nobody knows this symbol" are different problems and the alerts topic has to tell them apart.

Three classes, and the shape is the template for any code transform:

| class | what it is |
|---|---|
| `EnricherProperties` | `@ConfigurationProperties("enricher")`: the resource name, the symbol tag, the two maps, the on-miss policy. Its own prefix, because it is the application's, not the framework's |
| `EnricherConfiguration` | the one `@Bean`, **named** `instrumentEnricher` — the name a `transforms: [ { bean: … } ]` step resolves. It looks the table up in the `ResourceRegistry` *once*, at boot, so a wrong `enricher.resource` fails the application with the names that do exist |
| `InstrumentEnricher` | the transform: stateless apart from `hits()`/`misses()`, thread-safe, never writes into the map it is given |

The column a `set` entry names is matched to the label the driver reports **ignoring case**:
PostgreSQL reports an unquoted `sedol` as `sedol` and H2 as `SEDOL`, and a configuration
that enriched everything against one database and nothing against the other would be the
quiet kind of wrong. A column the query does not return at all is warned about once in the
log and left unwritten.

## The configuration

The deployable application is
`amps-connectors/config/local/streams/instrument-enricher/application.yml`, layered over
this module's baked `application.yml` (the `enricher:` defaults, no connectors) and
`config/local/common/` (the AMPS endpoint). The instance file owns four blocks:

```yaml
amps-connectors:
  resources:                                  # the reference table
    - name: instruments
      jdbc:
        url: "${JDBC_URL:jdbc:postgresql://${JDBC_HOST:localhost}:5432/refdata}"
        username: refdata_ro
        password: "${JDBC_PASSWORD:}"
        query: SELECT symbol, sedol, isin, currency, ric FROM instruments
        key-columns: [ symbol ]
        reload-interval: 5m                   # 0 = reload only when told to
  control:                                    # commands, over AMPS (or Kafka)
    enabled: true
    target: instrument-enricher
    source: { amps: { topic: connectors/control, mode: SUBSCRIBE } }
  alerts:                                     # reports, onto a journalled JSON topic
    application: instrument-enricher
    suppress-repeats: 30s
    amps: { topic: connectors/alerts }
  connectors:
    - name: orders-enriched
      format: FIX
      source: { kafka: { topic: orders.fix, group-id: amps-connectors-orders-enriched, … } }
      filter: { rules: [ { field: "35", in: [D, G, F] } ] }
      transforms:
        - drop: [ "10" ]                      # the session's checksum
        - bean: instrumentEnricher            # THE code step
        - rules:                              # conditional actions, counted per rule
            - name: limit-without-price
              when: "#f['40'] == '2' && !#f.containsKey('44')"
              then: { alert: { code: LIMIT_WITHOUT_PRICE, message: "limit order #{#f['11']} has no price" } }
            - name: large-notional
              when: "#num(#f['38']) * #num(#f['44']) > 1000000"
              then: { set: { "5001": LARGE } }
      amps: { topic: sow/connectors/orders, message-type: fix, key: { fields: ["11"], mode: SERVER } }

enricher:                                     # the application's own block (baked defaults)
  resource: instruments
  symbol-tag: "55"
  set: { "48": sedol, "15": currency }
  literals: { "22": "2" }
  on-miss: PASS                               # PASS | DROP
```

Two placeholders are worth knowing. `JDBC_HOST` moves the database the way the other
`*_HOST` variables move their endpoints (the compose stack sets it to
`host.containers.internal`); `JDBC_URL` replaces the **whole** URL, which is how a run
against something other than PostgreSQL is done — and it is a placeholder rather than a
`--amps-connectors.resources[0].jdbc.url=` override because a list is bound from *one*
property source: a resource overridden element-wise on the command line would be a
resource with a URL and nothing else.

The `enricher.set` and `enricher.literals` maps are merged over the baked defaults, as
Spring Boot merges any map property; to switch a default entry off, set it blank
(`"15": ""`).

## Running it

Against the flow these topics live in (`sow/connectors/orders`, `connectors/control` and
`connectors/alerts` are all declared in `server/config/flows/amps-connectors/amps-config.xml`):

```bash
AMPS_FLOW=amps-connectors ./server/scripts/amps.sh start
```

**Against a local PostgreSQL.** Create the table and its five seeded symbols (`K-0`..`K-4`;
the simulator's sixth, `K-5`, is deliberately absent) with the module's own seed file —
portable SQL, re-runnable — then start the application with the config tree and the
credentials the instance file names:

```bash
psql -h localhost -U refdata_ro -d refdata -f amps-connectors/apps/instrument-enricher/sql/instruments.sql

JDBC_PASSWORD=… ./gradlew :amps-connectors:apps:instrument-enricher:bootRun \
    --args="--spring.config.additional-location=file:amps-connectors/config/local/common/,file:amps-connectors/config/local/streams/instrument-enricher/"
```

That reads Kafka (`${KAFKA_HOST:localhost}:9092`, topic `orders.fix`). With no broker to
hand, `--source-driver=SIMULATED` swaps the feed for the in-process generator; the instance
file carries a `simulated:` template for exactly this — a new limit order per tick over six
symbols, five of which the seed knows, so the run shows the enrichment *and* one
`UNKNOWN_SYMBOL`.

**Against H2, in memory, with nothing installed.** `bootRun` (and only `bootRun` — not the
jar, not the image) carries the H2 driver, so the same seed file can be loaded by the URL
itself. `INIT=RUNSCRIPT` runs the script on *every* connection, and the resource opens one
per load, which is why the seed inserts each row only when its symbol is absent:

```bash
JDBC_URL="jdbc:h2:mem:refdata;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'amps-connectors/apps/instrument-enricher/sql/instruments.sql'" \
./gradlew :amps-connectors:apps:instrument-enricher:bootRun \
    --args="--spring.config.additional-location=file:amps-connectors/config/local/common/,file:amps-connectors/config/local/streams/instrument-enricher/ --source-driver=SIMULATED"
```

Then look at the SOW (SEDOL in 48, `22=2`, currency in 15, no tag 10) — raw, or through
this repo's `amps-cli` with the tags named and the enumerations decoded:

```bash
utils/bin/ampsToFileSOW.sh --topic sow/connectors/orders --out /tmp/orders.fix
./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/fix --topic sow/connectors/orders --mode snapshot --output nvfix"
```

and at the status the application logs every `status-interval`, which now says two things
a config-only application's does not — the rule counters at the end of the connector's
line, and the resource beneath it:

```
connector status:
  orders-enriched          RUNNING   sow/connectors/orders        received=1200 published=1200 batches=240 failed=0 rejected=0 filtered=0 dropped=0 ignored-deletes=0 rules[limit-without-price=0,large-notional=0]
resource status:
  instruments AVAILABLE rows=5 loaded=2026-09-19T14:00:00Z reloads=0 failures=0
```

**In the fleet.** `amps-connectors/scripts/amps-connectors-compose.sh local build` builds
`localhost/amps-instrument-enricher:local` (the compose script picks this module's image
over the generic runner's because `apps/instrument-enricher/` exists), and
`… local up streams` runs it with the two configuration directories mounted, expecting a
PostgreSQL at `JDBC_HOST` and a Kafka at `KAFKA_HOST`.

### The control message

The application subscribes to `connectors/control` (JSON) and answers to commands whose
`to` is absent, `all`, or its own name. After a change to the `instruments` table that
cannot wait for the five-minute timer:

```json
{"command":"reload","target":"instruments","to":"instrument-enricher","requestId":"r-1"}
```

`"target":"all"` (or no target) reloads every reloadable resource. `{"command":"status"}`
logs the status lines above at once and *replies* with an `INFO` alert, code `STATUS`,
whose details carry the same lines — the alerts topic being the application's only outbound
channel. A command nobody handles is `COMMAND_UNKNOWN`, a payload that is not a command is
`COMMAND_INVALID`, a handler that threw (a reload of a table that does not exist, a database
that refused) is `COMMAND_FAILED` with the request id in its details, and all three are
`WARN`s on the alerts topic. An application adds commands of its own by declaring
`CommandHandler` beans.

Publish it with any JSON client — 60East's `spark`, or a few lines over the Java client:

```java
Client client = new Client("ops");
client.connect("tcp://localhost:9007/amps/json");
client.logon();
client.publish("connectors/control",
        "{\"command\":\"reload\",\"target\":\"instruments\",\"to\":\"instrument-enricher\",\"requestId\":\"r-1\"}");
```

### The alert you get on a miss

Every alert is one flat JSON object on `connectors/alerts` (and in the log at its own
severity). An order whose symbol the table does not hold:

```json
{"timestamp":"2026-09-19T14:00:00.123Z","application":"instrument-enricher","severity":"WARN",
 "code":"UNKNOWN_SYMBOL","message":"symbol K-5 is not in instruments; the order passes through unenriched",
 "connector":null,"repeats":0,"details":{"symbol":"K-5","clOrdId":"ORD-K-5"}}
```

`connector` is `null` on purpose: a `bean:` step is resolved by name and the same bean may
run in several connectors' pipelines, so the transform does not guess. Repeat suppression
still applies, on the code: a feed of unknown symbols is the first alert at once and one
more when the 30-second window closes, carrying `"repeats":N` and the *last* symbol seen —
which is the trade to know about, because a storm across many symbols collapses too. The
other codes this application can raise are the framework's: `RESOURCE_LOAD_FAILED` (ERROR,
the table never loaded), `RESOURCE_RELOAD_FAILED` (WARN, the old copy stands),
`RESOURCE_UNAVAILABLE` (WARN, a record needed the table and there was none),
`LIMIT_WITHOUT_PRICE` from the rule above, and everything a connector raises about its
publisher and its source. Read them back the way the integration test does — a bookmark
subscription from the epoch, since the topic is journalled:

```bash
utils/bin/ampsToFileTxLog.sh --topic connectors/alerts --out /tmp/alerts.jsonl
```

## Adding a resource of your own

`resources[].jdbc` is the one *configured* kind. Anything else — a KDB client answering
market data by RIC, a gRPC stub — is an `AppResource` **bean** the application declares:
every such bean is collected by the `ResourceRegistry`, started before the connectors and
stopped after them under its own name, needs no `resources:` entry at all, and is found by
a transform exactly the way the enricher finds its table: `registry.lookup(name, Type.class)`
in a `@Bean` method, once. The two skeletons below are the shape; neither has a kdb+ or a
gRPC endpoint in this repository to be tested against, which is why they are prose here
rather than modules (a `resource-kdb` / `resource-grpc` module is the follow-up once one
exists).

**A KDB client, queried by RIC.** The resource owns the connection and its reconnect; the
transform holds the resource and asks it whether it is available before trusting an answer.
`isReloadable()` stays `false` — a client has nothing to reload — so a `reload` command
addressed to it is a `COMMAND_FAILED` saying so.

```java
public final class KdbMarketData implements AppResource {

    private final String host;
    private final int port;
    private final Alerts alerts;
    private volatile kx.c connection;          // the kdb+ client (com.kx:c)

    public KdbMarketData(String host, int port, Alerts alerts) { … }

    @Override public String name() { return "market-data"; }

    @Override public void start() throws Exception {
        connection = new kx.c(host, port);     // a throw here is logged and alerted by the
    }                                          // registry; retry on a thread of your own

    @Override public void stop() { kx.c c = connection; connection = null; if (c != null) c.close(); }

    @Override public boolean isAvailable() { return connection != null; }

    /** Last trade for a RIC, from a q function on the server; empty when it has none. */
    public Optional<BigDecimal> lastPrice(String ric) {
        kx.c c = connection;
        if (c == null) return Optional.empty();
        try {
            Object result = c.k("lastPrice", ric);
            return Optional.ofNullable((BigDecimal) result);
        } catch (Exception e) {
            alerts.raise(Alert.of(Alert.Severity.WARN, "MARKET_DATA_QUERY_FAILED", e.toString())
                    .withDetails(Map.of("ric", ric)));
            return Optional.empty();
        }
    }

    @Override public String status() { return name() + (isAvailable() ? " CONNECTED " : " DISCONNECTED ") + host + ":" + port; }
}
```

```java
@Configuration(proxyBeanMethods = false)
class MarketDataConfiguration {

    @Bean                                      // auto-registered under name(); no resources: entry
    KdbMarketData marketData(Alerts alerts) {
        return new KdbMarketData("kdb-1", 5010, alerts);
    }

    @Bean(name = "priceStamper")               // a transforms: [ { bean: priceStamper } ] step
    RecordTransform priceStamper(ResourceRegistry registry) {
        KdbMarketData md = registry.lookup("market-data", KdbMarketData.class);   // once, at boot
        return (record, fields) -> {
            Object ric = fields.get("ric");                                        // set by the enricher
            // Fields.copy, never new LinkedHashMap<>(fields): a typed record's view is cloned, not flattened
            Map<String, Object> out = Fields.copy(fields);
            if (ric != null) md.lastPrice(ric.toString()).ifPresent(px -> out.put("5010", px));
            return out;
        };
    }
}
```

**A gRPC lookup.** Same lifecycle, a channel instead of a socket; the stub is created once
from the channel, and `isAvailable()` reads the channel's connectivity state rather than
guessing. The transform is the same three lines as above with a different call in the
middle.

```java
public final class GrpcLookup implements AppResource {

    private final String target;               // "dns:///refdata.example:9090"
    private ManagedChannel channel;
    private RefDataGrpc.RefDataBlockingStub stub;

    public GrpcLookup(String target) { this.target = target; }

    @Override public String name() { return "refdata-grpc"; }

    @Override public void start() {
        channel = ManagedChannelBuilder.forTarget(target).usePlaintext().build();
        stub = RefDataGrpc.newBlockingStub(channel);
    }

    @Override public void stop() { if (channel != null) channel.shutdown(); }

    @Override public boolean isAvailable() {
        return channel != null && channel.getState(false) != ConnectivityState.SHUTDOWN
                && channel.getState(false) != ConnectivityState.TRANSIENT_FAILURE;
    }

    public Optional<Instrument> bySymbol(String symbol) {
        try {
            return Optional.of(stub.withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                    .lookup(LookupRequest.newBuilder().setSymbol(symbol).build()).getInstrument());
        } catch (StatusRuntimeException e) {
            return Optional.empty();           // NOT_FOUND and a deadline both read as a miss
        }
    }
}
```

The dependency rule that makes all of this safe to wire: the transform registry is built by
instantiating every `RecordTransform`, a transform holds resources, and the resource
registry is built from the resources, their factories and the alert manager. That is a
chain as long as no resource and no alert sink depends on a transform or on the connector
manager — a resource that wants to raise takes `Alerts`, which is earlier in the chain.
