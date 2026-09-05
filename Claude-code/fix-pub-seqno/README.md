# fix-pub-seqno

A FIX publisher that loses its AMPS connection and recovers **without losing
or duplicating a message**, by asking AMPS for the last sender sequence number
(tag 8888) it recorded and republishing only the gap.

```bash
# 1. AMPS on the flow that declares the journalled, sender-keyed topic
AMPS_FLOW=fix-pub-seqno ./server/scripts/amps.sh start

# 2. the guided sequence: publish -> crash -> recover -> subscribe -> naive
./gradlew :Claude-code:fix-pub-seqno:run --args="all"
```

This is the FIX resend-request problem with AMPS as the counterparty that
remembers what it received. The design is worked out first, in
[`docs/`](docs/README.md); the code is the second half.

## The problem

Every message the publisher sends carries a sender sequence number in **tag
8888**, assigned in order and recorded in the publisher's own durable
**outbox** before the message goes on the wire. When the connection drops --
a network blip, an AMPS restart, the publisher's own crash -- the publisher
has to answer one question before it sends anything else:

> what is the last tag 8888 AMPS actually recorded from me?

Call it **L**. Everything at or below L is safely stored; everything above it,
up to the outbox's last entry, has to be republished. Get L right and the
journal ends up holding an unbroken 1..N with nothing missing and nothing
twice. Guess it, and you either lose messages (resume too high) or duplicate
them (resume too low).

## Why the last number is enough

L would in general be a *set* -- "which of my messages do you have?" -- and is
a single number only because what AMPS holds is always a **prefix** of the
sender's sequence: 1, 2, ..., L with no holes. That holds because the
publisher sends in order over one connection, AMPS journals in arrival order,
and recovery only ever republishes *above* L. So "the last one" and "the whole
set" are the same fact, and the gap is exactly `(L, lastInOutbox]`. The
argument in full, and what breaks it, is
[`docs/01`](docs/01-problem-and-invariants.md).

## How L is found

Two server-side answers, in the tag-8888 space, needing no client-side file:

| | how | cost | catches |
| --- | --- | --- | --- |
| **SOW lookup** | the topic is a SOW keyed on the sender (tag 49), so it holds one record per sender: its newest message. One keyed query. | O(1) | the common case, in one read |
| **journal scan** | a bookmark subscription filtered to the sender, replayed from a timestamp before the outage, gives every message in the window; the max 8888 is L. | O(messages since the lookback) | a **gap** or a **duplicate** the lookup cannot see |

The recovery uses both: the lookup answers, the scan verifies. AMPS's own
client-library publish store is a third mechanism ([`docs/02`](docs/02-what-amps-already-does.md))
-- it replays in-flight messages automatically and lets the server reject
duplicates by sequence number -- but it does not cover the case where the
store file itself is lost, and its numbers are not tag 8888. The recommended
production shape is **both**: the publish store for the transient case, this
recovery for the case it cannot cover. The demo publisher runs without one so
that every step is a plain, observable command.

## What the code is

| package | what |
| --- | --- |
| `outbox` | the append-only `8888 -> payload` log; assigns the next number, refuses a hole, forces each entry to disk before it returns |
| `fix` | a small immutable FIX message over the AMPS client's own `FIXBuilder`/`FIXShredder`, and a deterministic order feed |
| `publish` | the two locators (SOW and journal), the **pure** `GapRecovery` decision, the `SequencedPublisher` (enqueue then transmit, split so a crash can be simulated), and the `PublisherRecovery` that runs the seven-step procedure |
| `subscribe` | a bookmark-store subscriber, and the per-sender `SequenceTracker` that turns "resume" into "resume with no gap" and makes an at-least-once redelivery harmless |

`GapRecovery` is where the failure matrix lives, and it is a pure function of
three numbers and a scan result -- so every row of that matrix is a unit test
with no server (`GapRecoveryTest`).

## The demo, phase by phase

`./gradlew :Claude-code:fix-pub-seqno:run --args="<phase>"`

| phase | what it shows |
| --- | --- |
| `publish` | send N orders; read L back from both the SOW and the journal |
| `crash` | record M more in the outbox, send only K, drop the link without flushing -- the classic persist-then-die gap |
| `recover` | ask AMPS for L, republish exactly `(L, lastInOutbox]`, verify the two sides agree |
| `subscribe` | resume a bookmark subscription and report every 8888 as in-sequence, duplicate or gap |
| `naive` | a second sender resends below L; the scan reports the duplicates and the subscriber drops them |
| `all` | the five above against one instance |
| `reset` | delete this demo's client-side state |

Because each phase connects its own client, `crash` and `recover` can be run
in separate invocations with a real server restart -- or a `podman kill` --
in between:

```bash
./gradlew :Claude-code:fix-pub-seqno:run --args="publish --count 10"
./gradlew :Claude-code:fix-pub-seqno:run --args="crash --count 6 --sent 2"
./server/scripts/amps.sh restart
./gradlew :Claude-code:fix-pub-seqno:run --args="recover"
./gradlew :Claude-code:fix-pub-seqno:run --args="subscribe"
```

Point it at another sender or instance without editing anything:
`-Dseqno.sender=PUB-B`, `-Dseqno.uri=tcp://host:9007/amps/fix`,
`-Dseqno.lookbackHours=48`.

## The server side

[`server/config/flows/fix-pub-seqno/amps-config.xml`](../../server/config/flows/fix-pub-seqno/amps-config.xml)
declares one topic, two ways at once:

```xml
<SOW>
  <Topic>
    <Name>fix/seqno/orders</Name>
    <MessageType>fix</MessageType>
    <Key>/49</Key>                      <!-- one SOW record per sender: the checkpoint -->
    <FileName>./sow/fix-seqno-orders.sow</FileName>
    <Durability>persistent</Durability>
  </Topic>
</SOW>
<TransactionLog>
  <Topic><Name>fix/seqno/orders</Name><MessageType>fix</MessageType></Topic>
</TransactionLog>
```

The transaction log is the system of record -- bookmark subscriptions and the
verification scan both read it. The SOW keyed on tag 49 is *not* an order
blotter; it is a publisher checkpoint table, one row per sender holding that
sender's most recent message, so "what did you last get from me?" is a single
keyed read and the message itself is the checkpoint.

## Tests

```bash
./gradlew :Claude-code:fix-pub-seqno:test              # unit tests, no server
AMPS_IMAGE=<your-image> \
  ./gradlew :Claude-code:fix-pub-seqno:integrationTest  # crash/recover against a real container
```

The unit tests cover the outbox invariants, the FIX codec, the full
reconciliation matrix (`GapRecovery`), and the subscriber's sequence check.
The integration test (`FixPubSeqnoIT`) drives publish, a real mid-batch
disconnect, recovery, and the subscriber against a throwaway AMPS instance,
and asserts the numbers: after a crash that leaves AMPS holding 1..12 while
the outbox holds 1..16, recovery republishes exactly four messages and the
journal ends with an unbroken 1..16. It **skips** rather than fails when
`AMPS_IMAGE` is unset, so `./gradlew build` stays green on a machine without
one; see the harness notes in
[amps-test-harness](../../amps-test-harness/README.md).

## What this deliberately does not do

- **It does not make the AMPS sequence number equal to tag 8888.** That is the
  clean next step -- a custom `Store` whose backing file is the outbox, letting
  the logon acknowledgement answer L for free and the server reject duplicates
  on 8888 -- but it has constraints (contiguous numbers, a value that starts
  above 1) that cannot be validated without a live server, so it is described
  in [`docs/03`](docs/03-design-options.md) and left unbuilt.
- **It does not deduplicate at the server without a publish store.** The demo
  publisher's `naive` phase shows exactly this: a resend below L lands in the
  journal twice, and only the subscriber's 8888 check keeps a consumer from
  double-processing. Adding a publish store (the production recommendation)
  moves that rejection to the server.
- **It says nothing about ordering across senders.** Tag 8888 is per sender;
  two senders interleave in arrival order and that is all.
