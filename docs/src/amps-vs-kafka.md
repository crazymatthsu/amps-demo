# AMPS vs Kafka

Both move messages between processes. They disagree about what a "topic" is, and
almost every other difference follows from that.

**Kafka's topic is a partitioned, durable log.** The log is the product. State is
something consumers derive by reading the log, and everything else — compaction,
consumer groups, Streams, ksqlDB — exists to make deriving state tolerable.

**AMPS's topic is a message stream that the server can also index and store by
key.** The current value of each key is maintained by the broker as messages flow
through, so a client can ask "what is true now?" without reading any history.
The transaction log is an optional second thing you switch on per topic when you
also need "what happened?".

That is the whole comparison. The rest is consequences.

---

## Side by side

| | AMPS | Kafka |
| --- | --- | --- |
| Topic creation | Publish to it. No config, no restart. | Provisioned resource with partitions and directories. |
| Practical topic count | Hundreds of thousands; the topic name is a routing dimension. | Thousands; partition count is a cluster-wide budget. |
| Current state | `sow` query answers from the server's keyed store. | Read a compacted topic to its end, or run a state store. |
| Snapshot + live | One command (`sow_and_subscribe`), atomic, no gap or duplicate. | Application-level: read to end-of-log while buffering live. |
| Server-side filtering | Content filter on the payload; only matches leave the broker. | Consumer receives everything and discards. |
| Partial updates | `delta_publish` merges fields server-side; only the delta is journalled. | Whole record every time; the log stores every byte. |
| "This no longer matches" | OOF message tells the subscriber. | No equivalent; the view silently drifts. |
| Record TTL | `<Expiration>` per topic or per message. | Retention by time/size per topic; compaction keeps the last value per key indefinitely. |
| Consumer position | Bookmark held by the subscriber. | Offset committed to the broker per consumer group. |
| Work sharing across consumers | Queue topics (a separate feature). | Built in: consumer groups over partitions. |
| Ordering unit | Per topic. | Per partition. |
| Payload awareness | Parses JSON/FIX/XML/etc. — that is how filters, keys and deltas work. | Opaque bytes. |
| Scale-out model | Vertical first; replication for HA and distribution. | Horizontal by partition, from the start. |

---

## The four things that actually change how you build

### 1. State is a query, not a rebuild

The most common Kafka pattern in a trading or risk system is: publish updates to a
compacted topic, and every consumer that needs current state reads the topic from
the beginning to materialise it. Startup time grows with history. Everyone
implements the same materialisation. A bug in it is a per-service bug.

In AMPS the broker already holds current state, so a starting client issues a
query and is immediately current. Restart time is independent of how much history
exists — the `recovery` demo shows a restart where the SOW is queryable straight
away and no replay happens at all.

The trade-off is real: the SOW is a store the broker maintains, so the broker owns
memory and disk proportional to your key space, and you must think about
`<Expiration>` (see [transaction-log-sizing.md](transaction-log-sizing.md)).
Kafka's broker stays dumber and pushes that cost to consumers.

### 2. The broker understands the payload

AMPS parses the message. A filter like `/quantity > 500 AND /side = 'SIDE_BUY'`
runs inside the server, and only matching messages leave it. A SOW key is a JSON
pointer into the payload. A delta is merged field by field.

This is why this demo uses **protobuf as the schema and JSON as the encoding**
rather than binary protobuf: binary would make the payload opaque and every
feature above would stop working. See
[protobuf-json-and-amps.md](protobuf-json-and-amps.md).

Kafka brokers never look inside a message, which is why they scale the way they do
and why filtering is the consumer's problem.

### 3. Topics are free, so use them as an addressing scheme

`events.trade.us.equity` is not a resource you create. Publish to it and it
exists; subscribe to `^events\.trade\..*` and you get it plus everything created
later. The `dynamic-topics` demo builds five topics and catches all of them with
one subscription that existed first.

The Kafka equivalent — one topic per instrument, per desk, per session — hits
partition-count limits quickly, so the usual design puts the routing key *inside*
the message and filters client-side. Which works, and costs bandwidth for every
consumer.

### 4. Consumer position is the subscriber's business

An AMPS bookmark lives in the subscriber's store. Two clients reading the same
topic never interact; there is no group membership, no rebalance, no
stop-the-world when a member joins.

The flip side is that AMPS gives you no automatic work distribution — three
instances of a service each subscribing to the same topic each get *every*
message. When you want competing consumers you use a queue topic, which is a
deliberate, separate feature rather than the default behaviour.

---

## Where Kafka is the better answer

Being honest about this matters more than the pitch:

- **Horizontal scale on volume.** Kafka partitions across a cluster as a matter of
  course. AMPS scales up first and out second.
- **Work distribution.** Consumer groups are the natural model for a pool of
  workers sharing a queue. AMPS queues do this but it is not the default idiom.
- **Long-horizon retention.** Keeping a year of everything is ordinary in Kafka,
  and tiered storage makes it cheap. In AMPS a journal that long is usually the
  wrong shape — the SOW holds current state and the journal covers a replay
  window (see [transaction-log-sizing.md](transaction-log-sizing.md)).
- **Ecosystem.** Connectors, schema registry, stream processors, and the fact that
  most engineers have used it.
- **Opaque or huge payloads.** If the broker cannot parse it, AMPS's advantages
  do not apply, and you are paying for capabilities you cannot use.

## Where AMPS is the better answer

- Consumers need **current state fast and often** — a starting GUI, a risk
  process, anything that asks "what is the book right now?".
- The working set is **keyed and updated repeatedly**, so last-value-per-key is
  the natural shape.
- Records are **wide but change narrowly** — delta publishing turns a bandwidth
  and storage problem into a rounding error, in both directions.
- Consumers want **different slices** of the same stream, and you would rather
  filter once in the server than N times over the network.
- **Latency matters** and you would rather not have a materialisation layer
  between the broker and the answer.

## A rough decision rule

> If consumers mostly ask *"what happened?"*, the log is the right primitive and
> Kafka is a mature implementation of it.
>
> If consumers mostly ask *"what is true now?"* — and then want to be told when it
> changes — AMPS gives you that as one command instead of as an architecture.

Plenty of systems need both. Running AMPS for the query-and-subscribe surface and
Kafka for durable fan-out to the wider estate is a common and reasonable split.
