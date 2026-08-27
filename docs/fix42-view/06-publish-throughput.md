# Publish throughput: what is actually slow

Measured on AMPS 5.3.5.135, x86_64 image under emulation on Apple Silicon,
loopback. Reproduce on your own hardware:

```bash
AMPS_IMAGE=<your-image> ./gradlew :fix42-publisher:publishBenchmark
```

Load: 1,500 order chains, 9,000 FIX messages, **24,000 publishes** (most
messages route to two or three topics).

| | ms | msg/s |
| --- | ---: | ---: |
| A. `publishFlush` after **every** publish | 9,232 | 2,599 |
| **B. flush once at the end** | **1,681** | **14,277** |
| C. + `setPublishBatching(8KB, 10ms)` | 1,739 | 13,801 |
| D. + `setPublishBatching(64KB, 10ms)` | 1,716 | 13,986 |
| E. + `setPublishBatching(256KB, 50ms)` | 1,705 | 14,076 |
| F. real publisher, logging at INFO | 1,916 | 12,526 |
| G. real publisher, logging at WARN | 1,724 | 13,921 |

Routing and tag selection for all 24,000 publishes: **40 ms**, about 1.6 µs
each. It is not the bottleneck and does not appear again below.

---

## 1. The first thing to check is whether publishing is slow at all

A `bootRun` of the demo takes about **3.3 seconds**. Of that, publishing is
**25 ms**:

```
22:21:45.243  connected to AMPS
22:21:45.276  publishing 40 FIX 4.2 messages across 7 order chains
22:21:45.301  done: 14 full publishes, 89 delta publishes
```

The other 99% is Gradle, JVM start, Spring context, and the AMPS connection.
For a 103-publish demo that is the whole story, and no amount of publisher
tuning will move it. Measure the publish window specifically before tuning
anything.

---

## 2. Flushing per message costs 5.3×

This is the dominant effect, larger than everything else combined.

`Client.publish` is **asynchronous**: it writes into the transport and returns.
`publishFlush` blocks until AMPS acknowledges persistence. Calling it per
message converts a pipelined stream into a request/response round trip, and
throughput collapses from 14,277 to 2,599 msg/s.

The shipped `AmpsDeltaPublisher` already does the right thing -- `send()` never
flushes, and `flush()` is called once when the run finishes. If a publisher of
yours is slow, this is the first thing to look for, and it is easy to introduce
by accident: every ad-hoc probe written while developing this repo flushed per
message, because that is what makes a five-line script behave predictably.

Flush when you need a barrier -- shutdown, or before reading back what you
wrote -- not per message.

---

## 3. Batching across different topics: available, but it changed nothing here

The question this document started from: *can publishes be batched when they
are routed to different SOW topics?*

**Yes.** `Client.setPublishBatching(batchSizeBytes, batchTimeoutMillis)`
buffers outgoing publishes and sends them together. It operates at the
**transport** layer, below the topic, so a batch may carry publishes for
`sow/parent/orders`, `sow/parent/execs` and `sow/child/orders` alike. Nothing
about the routing has to change.

**But it measured no faster** -- C, D and E all sit within noise of B, and the
8KB setting was slightly worse. The reason is that the win it targets is
already being had: with a single flush, publishes are pipelined into the socket
without waiting, so on loopback the syscall overhead it removes was not the
constraint. Its own documentation is explicit that it is for "messages that are
small compared to the size of tcp buffers".

Where it plausibly *does* pay, and this benchmark cannot show it: a real
network with meaningful latency, a saturated NIC, or a much smaller per-message
size. Run the benchmark there before adopting it. Note the trade it makes --
`batchTimeoutMillis` is added latency for a partially filled batch, which is a
poor bargain for order flow that is latency-sensitive rather than
throughput-bound.

---

## 4. Logging cost, and an easy mistake

INFO logging costs about 15% (F vs G). Two things worth knowing:

The publisher logs one line per publish, and that line renders the payload.
**slf4j's `{}` defers formatting, not argument evaluation** -- so an unguarded

```java
log.info("... {}", instruction.payload().printable());
```

renders the payload on every publish even at WARN, where the line is discarded.
`printable()` is a `FIXBuilder` allocation plus a string copy. The call is now
guarded by `log.isInfoEnabled()`, which is why G matches B exactly rather than
trailing it.

For a production publisher, turn the per-message line off:

```yaml
logging:
  level:
    com.demo.amps.fix42: WARN
```

It is left at INFO by default because this module is a demonstration and the
published payloads are the thing worth seeing.

---

## 5. If you need more than ~14k msg/s

In rough order of return:

1. **Confirm the flush pattern** (§2). Nothing else comes close.
2. **Turn off per-message logging** (§4): ~15%.
3. **Publish fewer messages.** Each FIX message here becomes two or three
   publishes because routes fan out to a blotter and an audit topic. Dropping a
   destination is a config edit, and it is a linear saving -- worth asking
   whether every consumer of `_audit` actually exists.
4. **Shard across connections.** Ordering only has to hold *within* an order
   chain: the chaining module resolves 11/41 per chain, and chains are
   independent. So N connections, each owning a disjoint set of chains, scale
   nearly linearly. Two chains of the same order must never be split across
   connections, or their messages can be reordered relative to each other.
5. **Only then** consider `setPublishBatching`, measured on your own network.

Not on this list: the routing layer. At 1.6 µs per publish it would have to get
about 100× worse before it mattered.
