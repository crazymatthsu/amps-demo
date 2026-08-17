# Native FIX and NVFIX message types

Everything else in this repository normalizes payloads to JSON. This document
and the `fix-native` / `nvfix-native` demos do the opposite: the payload on the
wire **is** the FIX message, and AMPS parses it natively.

```bash
./gradlew :clients:run --args="fix-native"
./gradlew :clients:run --args="nvfix-native"
```

## The two formats

Both are SOH-separated (`0x01`, shown as `|` below) field=value pairs. They
differ only in how a field is named:

```
fix     35=8|9001=EVT-003|11=A1|37=ORD-7|17=EXEC-A1|55=AAPL|54=1|39=1|150=1|20=0|14=200|151=300|6=100|32=200|31=100|
nvfix   MsgType=8|EventId=EVT-003|ClOrdID=A1|OrderID=ORD-7|ExecID=EXEC-A1|Symbol=AAPL|Side=1|OrdStatus=1|...
```

That naming difference propagates into the server config and every filter:

| | `fix` | `nvfix` |
| --- | --- | --- |
| SOW key | `<Key>/37</Key>` | `<Key>/OrderID</Key>` |
| status filter | `/39 = '2'` | `/OrdStatus = '2'` |
| chain history filter | `/11 = 'A1' OR /41 = 'A1'` | `/ClOrdID = 'A1' OR /OrigClOrdID = 'A1'` |
| readability | tags — you need the dictionary | names — self-describing |
| payload size | smallest | between fix and JSON |

Everything demonstrated elsewhere in this repo works unchanged on these topics:
SOW queries, `sow_and_subscribe`, OOF, content filters, expiration, the
transaction log and bookmark replay. The message type changes how fields are
*referenced*, not what the server can do.

## The topics

Declared in [`amps-config.xml`](../../server/config/amps-config.xml), one pair
per encoding, mirroring the JSON `fix.*` pattern:

```xml
<Topic>
  <Name>fix.native.events</Name>
  <MessageType>fix</MessageType>
  <Key>/9001</Key>                <!-- custom event-id tag: every message its own record -->
</Topic>
<Topic>
  <Name>fix.native.orders</Name>
  <MessageType>fix</MessageType>
  <Key>/37</Key>                  <!-- OrderID: last report per order = current state -->
</Topic>
```

plus the `nvfix.native.*` twins keyed `/EventId` and `/OrderID`. The events
topics are journalled (they are the system of record); the orders topics are
not (they hold only the latest 35=8 per order and are derivable).

Two details that earn their comment in the config:

- **The events key is a custom tag.** FIX reserves a user-defined range;
  tag `9001` carries a unique event id, the same job `/eventId` does on the
  JSON topics. Keying every message uniquely turns a last-value store into a
  queryable event log.
- **Requests must not be published to the orders topics.** A 35=D/G/F carries no
  OrderID (37), and a SOW publish that lacks its key field fails. That is a
  feature — the key declares what belongs in the topic — and it is why the
  publisher routes reports to both topics but requests only to events.

## Client side

The message type is chosen in the **connection URI**, not per command — the same
transport serves all of them:

```java
Client publisher = new Client("gateway");
publisher.connect("tcp://127.0.0.1:9007/amps/fix");     // or /amps/nvfix, /amps/json
publisher.logon(timeout);
```

Payloads are built and parsed with the client library's own helpers — no
hand-rolled string splicing:

```java
// build
FIXBuilder builder = new FIXBuilder(512, (byte) 0x01);
builder.append(35, "8").append(11, "A1").append(14, "200");
publisher.publish("fix.native.events", new String(builder.getBytes(), 0, builder.getSize(), UTF_8));

// parse
Map<Integer, CharSequence> tags = new FIXShredder((byte) 0x01).toMap(message.getData());
```

`NVFIXBuilder` / `NVFIXShredder` are the named-field equivalents. In this repo
the wrapping lives in
[`FixWire`](../../common/src/main/java/com/demo/amps/common/fix/FixWire.java),
which encodes the same `OrderEvent` objects the JSON demos use — one simulator
([`OrderLifecycleSimulator`](../../common/src/main/java/com/demo/amps/common/fix/OrderLifecycleSimulator.java))
feeds all three encodings.

## The simulator

Four scripted FIX 4.2 lifecycles covering all five message types and every
ending that matters:

| chain | scenario | exercises |
| --- | --- | --- |
| A1→B1 (AAPL, ORD-7) | replace-and-reject | D, ack, partial, **G** with a fill crossing the pending window, replace ack (39=5), **F**, **9** cancel reject, PossDup resend of the same ExecID, final fill |
| M1 (MSFT, ORD-8) | cancelled | D, ack, partial, **F**, pending cancel (39=6), terminal cancel (39=4, leaves zeroed) |
| G1 (GOOG, ORD-9) | rejected | D, reject (39=8) |
| T1 (TSLA, ORD-10) | plain fill | D, ack, two partials, filled (39=2) |

The scripts are deterministic and internally consistent (`38 = 14 + 151` on
every working report — and deliberately *not* on the terminal cancel, where
LeavesQty is zeroed by definition; the tests encode both facts). The same script
replayed through `FixOrderStateMachine` reaches the scripted endings, which is
tested — so the simulator, the state machine, and the wire codecs cannot drift
apart silently.

## Choosing between the three encodings

| | JSON (+ protobuf schema) | fix | nvfix |
| --- | --- | --- | --- |
| server parses / filters / keys | yes | yes | yes |
| human-readable records | yes | no | mostly |
| schema & codegen | protobuf | FIX dictionary | FIX dictionary |
| nested structures / delta merge on subfields | yes | flat tags only | flat tags only |
| payload size | largest | smallest | middle |
| gateway work when the source is FIX | translate | none — forward as-is | rename tags |

A reasonable rule: if the data **arrives as FIX and leaves as FIX**, carrying it
natively avoids a translation layer and keeps the audit trail wire-faithful. If
the data is consumed by services, GUIs and analysts, JSON's readability and the
protobuf schema tooling usually pay for their bytes. NVFIX is the compromise
when you want FIX-shaped flat records that a human can still read in the admin
console.

Delta messaging deserves one note: AMPS supports delta publish/subscribe on FIX
and NVFIX types too, but the records are flat — a delta is "these tags changed".
The nested-merge behaviour the JSON instrument demos rely on (`quote.bid` inside
a document) has no FIX equivalent, because FIX has no nesting.

## Verify on your build

Written, as everything here, without a live server to check against:

- **Field-reference syntax in `<Key>` and filters** for fix/nvfix types — tag
  numbers (`/9001`, `/37`) and names (`/OrderID`) are the expected forms;
  `./server/scripts/amps.sh validate` answers in a second.
- **Numeric comparison on FIX fields.** FIX values are text on the wire. The
  demos deliberately use only equality filters (`/39 = '2'`); before relying on
  a range filter like `/14 > 100`, confirm how your AMPS version compares
  fix-typed fields — the JSON topics do not have this ambiguity because JSON
  distinguishes numbers from strings.
- **Custom message-type definitions.** AMPS can also declare custom FIX variants
  (different separators, validation) in the config; the stock `fix` and `nvfix`
  types are used here.
