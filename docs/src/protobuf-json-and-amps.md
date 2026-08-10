# Protobuf as schema, JSON on the wire

## The choice

This project uses `.proto` files as the message contract and sends **canonical
protobuf JSON** as the payload. Binary protobuf is never used.

That is deliberate, and it is the opposite of what you would do on Kafka.

**Why protobuf for the schema.** It is a real IDL: types, field numbers, explicit
evolution rules, and code generation for every language a client might be written
in. The alternative — JSON with a document describing it — has no generated types
and no enforced compatibility story.

**Why JSON on the wire.** AMPS parses the payload. That is how content filters,
SOW keys, delta merges and projections work:

```xml
<Key>/orderId</Key>
```

```java
.setFilter("/quantity > 500 AND /side = 'SIDE_BUY'")
```

Send binary protobuf and AMPS sees opaque bytes. Every feature above stops working
and you are left with a fast pub/sub bus — which AMPS is, but it is not why anyone
chooses it.

The cost is real: JSON is larger and slower to parse than binary protobuf. For
this workload it buys server-side filtering, keying and merging, which save far
more than the encoding costs. If your messages are opaque blobs and you only need
transport, that trade goes the other way — and so does the choice of broker.

## The rules this imposes on the schema

Canonical protobuf JSON has behaviours that interact with AMPS in ways that are
easy to get wrong and hard to notice. Each is pinned by a test in
[`JsonCodecTest`](../../common/src/test/java/com/demo/amps/common/JsonCodecTest.java).

### 1. 64-bit integers become quoted strings

The one that bites. Canonical protobuf JSON renders `int64`, `uint64`, `fixed64`
and `sfixed64` as **strings**:

```json
{"quantity": "5000"}
```

AMPS compares JSON numbers numerically and JSON strings lexically. So
`/quantity > 500` against `"5000"` is a string comparison — `"5000" > "500"` is
true here, but `"1000" > "500"` is **false**, because `"1"` sorts before `"5"`.
The filter does not error. It silently returns the wrong rows.

**Rule: any field you filter on numerically must be `int32`, `float` or `double`.**

```proto
int32  quantity = 4;                    // JSON number -- filterable
double price = 6;                       // JSON number -- filterable
double updated_at_epoch_seconds = 11;   // seconds as a double, not int64 millis
```

If you genuinely need 64-bit range, carry it as `double` where precision allows,
or keep the `int64` for transport and add a `double` copy for filtering.

### 2. Field names become lowerCamelCase

`order_id` in the proto is `orderId` in the JSON. The SOW key must match:

```proto
string order_id = 1;
```
```xml
<Key>/orderId</Key>
```

Do **not** enable `preservingProtoFieldNames()` without changing the server config
to `/order_id`. Nothing errors if you get this wrong — every record simply lands
under an empty key, and the SOW holds exactly one row.

### 3. Default values are omitted unless you force them

By default protobuf JSON drops fields holding their default value, so
`quantity = 0` serializes as if `quantity` did not exist. A filter of
`/quantity < 10` then fails to match the record, and consumers see a missing field
rather than a zero.

`JsonCodec` uses `alwaysPrintFieldsWithNoPresence()` for full publishes so the
complete field set always travels. Deltas force exactly the changed fields — see
[delta-updates.md](delta-updates.md).

### 4. Enums serialize by name

```json
{"status": "ORDER_STATUS_FILLED"}
```

so filters read naturally:

```java
.setFilter("/status = 'ORDER_STATUS_FILLED'")
```

Keep `printingEnumsAsInts()` off: numbers would make filters unreadable and
brittle against enum renumbering.

### 5. Unknown fields must be ignored on parse

`JsonCodec` parses with `ignoringUnknownFields()`. A publisher on a newer schema
version will include fields older consumers do not know. Without this, rolling out
a schema change becomes a lock-step deployment across every consumer.

## Schema evolution

Ordinary protobuf rules, with one AMPS-specific caveat:

- **Adding an optional field** is safe. Old consumers ignore it, and AMPS filters
  on it only for records that carry it.
- **Removing a field** is safe on the wire but breaks any server-side filter or
  SOW key that references it. Grep the config before deleting a field.
- **Renaming a field** changes the JSON member name, so it is a breaking change
  for filters and keys even though protobuf considers field numbers canonical.
  In JSON encoding the *name* is the contract.
- **Changing a field's type** between `int32` and `int64` silently changes it from
  a JSON number to a JSON string. See rule 1. Treat this as breaking.
- **Never reuse field numbers**, as always.

The last two are the ones a protobuf-experienced team will not expect, because in
binary protobuf the name is irrelevant and int32/int64 are wire-compatible.

## Where the code lives

| file | role |
| --- | --- |
| [`order.proto`](../../common/src/main/proto/com/demo/amps/market/v1/order.proto) | the order entity; field types chosen for filterability |
| [`instrument.proto`](../../common/src/main/proto/com/demo/amps/market/v1/instrument.proto) | large record with a small volatile part |
| [`JsonCodec`](../../common/src/main/java/com/demo/amps/common/JsonCodec.java) | printers and parser, with the reasoning inline |
| [`DeltaBuilder`](../../common/src/main/java/com/demo/amps/common/DeltaBuilder.java) | minimal-diff computation and AMPS-equivalent merge |
| [`JsonCodecTest`](../../common/src/test/java/com/demo/amps/common/JsonCodecTest.java) | one test per rule above |

Generated Java lands in `common/build/generated/sources/proto/` — never checked in,
always regenerated by `./gradlew :common:generateProto`.

## A note on the alternative

AMPS also speaks FIX, NVFIX, XML, and composite message types, and can carry
opaque binary as `bson` or a raw blob. If you are already a protobuf shop and the
idea of JSON on the wire grates, the honest framing is:

> The payload format is what AMPS uses to give you server-side keying, filtering
> and merging. Pick the smallest format the server can parse. Between JSON and
> binary protobuf there is no contest — only one of them the server can read.
