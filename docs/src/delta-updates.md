# Delta updates

## The shape of the problem

An instrument snapshot in this demo is about 1.5 KB. Almost all of it is reference
data — name, identifiers, calendars, eligible venues — that is fixed for the life
of the instrument. A quote tick changes six numbers, about 70 bytes.

Publishing the whole snapshot on every tick means:

- ~1.5 KB across the network per update, per publisher;
- ~1.5 KB written to the transaction log per update;
- ~1.5 KB delivered to every subscriber, per update.

The static part is 95% of that and is already known to everyone involved.

## delta_publish

```java
DeltaBuilder.Delta delta = DeltaBuilder.between(previous, next, List.of("symbol"));
client.deltaPublish("instruments", delta.json());
```

sends:

```json
{"symbol":"AAPL","quote":{"bid":422.96,"ask":422.98,"last":422.97,"sequence":2},"revision":2}
```

AMPS merges that into the stored record server-side. The SOW ends up holding the
complete instrument; subscribers doing a `sow` query see the whole thing; and the
transaction log recorded 90 bytes instead of 1,500.

Measured by
[`journal-lab`](../../clients/src/main/java/com/demo/amps/clients/demos/JournalLabDemo.java),
that is roughly a **20x** reduction in both bytes published and journal growth for
this record shape.

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
