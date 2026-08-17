# Delta updates

## The shape of the problem

An instrument snapshot in this demo measures **1,328 bytes**. The reference block
alone — name, identifiers, calendars, eligible venues — is **751 bytes, 57% of the
record**, and is fixed for the life of the instrument. A quote tick changes the
quote, a revision counter and a timestamp: **134 bytes**.

Publishing the whole snapshot on every tick means:

- 1,328 B across the network per update, per publisher;
- 1,328 B written to the transaction log per update;
- 1,328 B delivered to every subscriber, per update.

Most of that is already known to everyone involved and is being re-sent anyway.

## delta_publish

```java
DeltaBuilder.Delta delta = DeltaBuilder.between(previous, next, List.of("symbol"));
client.deltaPublish("instruments", delta.json());
```

sends:

```json
{"symbol":"AAPL","quote":{"bid":121.47,"ask":121.49,"last":121.48,"sequence":2},"revision":2,"updatedAtEpochSeconds":1.786407295313E9}
```

AMPS merges that into the stored record server-side. The SOW ends up holding the
complete instrument; subscribers doing a `sow` query see the whole thing; and the
transaction log recorded 134 bytes instead of 1,328 — roughly a **10x** reduction
in both bytes published and journal growth for this record shape.

Of those 134 bytes, only **93** are the actual data change. The other **41** are
`updatedAtEpochSeconds`, a full-precision double stamped on every update. Drop it
and the same change costs 93 B, a **14x** reduction. Hold that thought — it comes
back below.

## Why this shrinks the transaction log

The mechanism is one sentence: **the journal stores the message that was
published, not the record that resulted.**

A `delta_publish` is its own command carrying its own payload. AMPS journals that
message, then separately merges it into the SOW. The two destinations receive
different things:

| | what it receives | how it grows |
| --- | --- | --- |
| SOW | the merged **full record** | one record per key — fixed, independent of update count |
| transaction log | the **delta message as published** | one entry per publish — grows without limit |

So the journal's size is `Σ(published message sizes)`. Shrink each message and the
journal shrinks proportionally. Nothing is lost, because the full record still
exists — in the SOW, exactly once, rather than re-serialised into the log on every
tick.

Put the arithmetic next to it. For a record of size `R`, a delta of size `d`, and
`N` updates to one key:

- **full publishes:** `R × N` — the static bytes are written `N` times
- **delta publishes:** `R + (d × N)` — the static bytes are written **once**, when
  the record is created

The ratio tends to `R/d` as `N` grows. With `R` = 1,328 and `d` = 134:

| updates | full | delta | ratio |
| --- | --- | --- | --- |
| 1 | 1,328 B | 1,462 B | 0.9x (a delta costs *more* for the first write) |
| 10 | 13 KB | 2.7 KB | 5.0x |
| 100 | 130 KB | 14 KB | 9.0x |
| 1,000 | 1.3 MB | 132 KB | 9.8x |
| 10,000 | 12.7 MB | 1.3 MB | 9.9x |

That first row is worth noticing: for a key written once and never updated, delta
publishing is a small loss. The saving comes entirely from **repetition**, which is
also why the technique matters most exactly where journals get big — many updates
to the same key.

### What bounds the saving

Three things stop the ratio going to infinity:

1. **Per-record journal overhead.** Every journal entry carries framing a delta
   cannot shrink: bookmark, topic, publisher and sequence identifiers, timestamps.
   Once the payload is small relative to that overhead, further shrinking the
   payload buys little. If the overhead were, say, 120 B per record, the 9.9x above
   would land nearer 5.7x. Measure your own with `journal-lab` rather than
   assuming — the exact overhead is an AMPS implementation detail.

2. **What you actually touch.** The delta contains every field that changed, not
   every field you *meant* to change — and at these sizes, bookkeeping fields stop
   being free. Measured on the payload above:

   | | bytes | ratio vs the 1,328 B record |
   | --- | --- | --- |
   | quote + revision (the real change) | 93 | 14.3x |
   | plus `updatedAtEpochSeconds` (a double) | 134 | 9.9x |

   The timestamp is **41 bytes, 31% of the delta**, and it costs a third of the
   available saving. `1.786407452235E9` is expensive because a double serialises
   to as many significant digits as it needs. Second-resolution as an `int32`
   would be ~10 B and would not vary in length run to run. If every update stamps
   a time, that field deserves as much thought as the payload.

   (This is also why a naive measurement can mislead: if you generate the "before"
   and "after" records in the same millisecond, the timestamp does not change, it
   drops out of the delta entirely, and you measure 93 B for something that costs
   134 B in production. `DeltaBuilderTest.documentedSizesStillHold` sets the
   timestamp explicitly for exactly this reason.)

3. **How much of the record is genuinely static.** Deltas help in proportion to the
   static fraction — 57% here. A bare quote with no reference data attached has
   nothing to omit, and delta publishing will do close to nothing for it.

### What does *not* shrink the journal

`sow_and_delta_subscribe` reduces bytes sent **to subscribers**. It does nothing to
the journal, because the journal records what was *published*, and that path is
unchanged. The two are independent:

- delta **publish** → smaller journal, smaller ingress
- delta **subscribe** → smaller egress, per subscriber

You can have either without the other. Only the first affects disk.

### Verify it on your build

This rests on the claim that AMPS journals the delta rather than the post-merge
record. That is what
[`journal-lab`](../../clients/src/main/java/com/demo/amps/clients/demos/JournalLabDemo.java)
exists to measure:

```bash
./gradlew :clients:run --args="journal-lab --symbols 10 --updates 500"
```

It sends identical logical updates to two topics — whole records to one, deltas to
the other — and reports journal growth for each. **If AMPS journalled merged
records, the two phases would cost the same**, and you would see it immediately.
Two minutes of measurement beats trusting this document.

## sow_and_delta_subscribe

The mirror image, on the outbound side:

```java
new Command("sow_and_delta_subscribe").setTopic("instruments").setFilter("/symbol = 'MSFT'")
```

The subscriber receives the full record once, then only changed fields on every
subsequent update — **regardless of how the data was published**. AMPS knows the
previous state, so it can compute the difference even when the publisher sent a
whole record. A screen showing 5,000 wide rows gets its picture once and then a
few bytes per change.

## The three rules that matter

### 1. Nested messages merge recursively

Changing `quote.bid` sends only `quote.bid`. `quote.ask` keeps its stored value.
This is what makes deep records cheap to update.

### 2. Arrays are replaced, never merged

Changing one level of the order book re-sends the entire ladder. JSON has no
positional merge, so there is nowhere to express "element 3 changed".

If a repeated field is on your hot path, model it as nested messages keyed by name
rather than as a list:

```proto
// Expensive to update: one changed level re-sends all of them.
repeated PriceLevel book = 4;

// Cheap: level_2 merges independently of level_1.
message Book {
  PriceLevel level_1 = 1;
  PriceLevel level_2 = 2;
  PriceLevel level_3 = 3;
}
```

`DeltaBuilderTest.arraysAreReplacedWholesale` pins this behaviour down.

### 3. A merge can set a field, not remove one

There is no way to say "delete this field" in a merge. When a field is present
before and absent after, `DeltaBuilder` sets `fullPublishRequired()` and the caller
falls back to a full publish:

```java
if (delta.fullPublishRequired()) {
    client.publish(topic, JsonCodec.toJson(next));
} else {
    client.deltaPublish(topic, delta.json());
}
```

In practice this is rare, because proto3 scalars have no presence — a field
"cleared" to zero is still a field set to zero, which merges fine. It matters for
submessages and for `optional` scalars.

## Why default values must be printed explicitly

The subtle one. Protobuf JSON omits fields at their default value, so a naive
delta that sets `filledQuantity` back to `0` would serialize as `{}` — and AMPS
would merge nothing, leaving the stale value in the SOW.

`JsonCodec.toDeltaJson` therefore forces exactly the changed fields to appear:

```java
JsonFormat.printer().includingDefaultValueFields(changedFieldDescriptors)
```

`DeltaBuilderTest.resettingToDefaultIsExplicit` is the guard. If you write your own
delta path, this is the bug you will hit.

## Predicting the merge locally

`DeltaBuilder.apply(base, delta)` applies AMPS's merge rules client-side, which
lets a test — or the `delta-publish` demo — assert that what the server stored is
what was intended, rather than eyeballing output.

Neither protobuf primitive can substitute for it, and both failure modes are
documented in `DeltaBuilderTest.protobufMergePrimitivesAreNotEquivalent`:

- `JsonFormat.Parser.merge` **throws** on a builder whose scalar fields are already
  set — it parses records, it does not merge them;
- `Builder.mergeFrom` **silently skips** source fields holding default values, so a
  reset-to-zero disappears.

## When not to bother

- **Small records.** Below a few hundred bytes the delta is a similar size to the
  record and the bookkeeping is not worth it.
- **Most fields change every time.** Market-data ticks where the whole quote moves
  gain little.
- **Non-SOW topics.** There is nothing to merge into; delta publishing needs a
  stored record.
- **Publishers that do not hold the previous state.** Computing a delta requires
  knowing what came before. If your publisher is stateless, either keep the last
  published record in memory (what `journal-lab` does) or publish in full and let
  `sow_and_delta_subscribe` do the saving on the outbound side — that path needs
  no publisher state at all.

That last option is worth emphasising: **you can get the subscriber-side saving
without changing your publishers at all.** Only the transaction-log saving
requires publishing deltas.
