# amps-quickfixj

JDK 21 library that loads a **QuickFIX/J data-dictionary XML** (FIX 4.2 or
another version) and converts AMPS native `fix` ↔ `nvfix` payloads.

This is *not* a FIX session engine. It does not depend on `amps-cli` or the
AMPS Java client. Encoding matches the rest of this repo: SOH (`\u0001`)
separated `tag=value` / `Name=value` pairs, every field including the last
terminated by SOH. See [`docs/src/native-fix-and-nvfix.md`](../docs/src/native-fix-and-nvfix.md)
and `FixWire` for the on-the-wire shape this module is translating.

Unlike `FixWire.nameOf` (a hardcoded tag table that leaves enum *values* as
codes), this converter takes meanings from the XML: `39=2` → `OrdStatus=Filled`.

```bash
./gradlew :amps-quickfixj:test
```

## Load a dictionary

Point the API at any QuickFIX/J `FIX*.xml` (path, stream, or string):

```java
import com.demo.amps.quickfixj.FixNvfixConverter;
import com.demo.amps.quickfixj.QuickFixDictionary;
import java.nio.file.Path;

QuickFixDictionary dict = QuickFixDictionary.fromPath(Path.of("FIX42.xml"));
// QuickFixDictionary.fromInputStream(in);
// QuickFixDictionary.fromXml(xml);

FixNvfixConverter conv = new FixNvfixConverter(dict);
```

## Full convert

```java
String fix = "35=8\u000111=A1\u000139=2\u000154=1\u0001151=0\u0001";

String nvfix = conv.fixToNvfix(fix);
// MsgType=ExecutionReport|ClOrdID=A1|OrdStatus=Filled|Side=Buy|LeavesQty=0|

String roundTrip = conv.nvfixToFix(nvfix);
// 35=8|11=A1|39=2|54=1|151=0|
```

Neither direction invents missing required fields (no BodyLength, no CheckSum,
no filler tags). If they are on the wire, they are converted; if they are not,
they stay absent.

## Partial / delta convert (AMPS SOW delta publish)

AMPS delta publish on `fix` / `nvfix` topics is "these tags changed". Convert
only what is present:

```java
// e.g. a fill delta: ClOrdID, OrdStatus, LeavesQty — nothing else
String deltaFix = "11=PARENT-AAPL-2\u000139=2\u0001151=0\u0001";

String deltaNvfix = conv.partialFixToNvfix(deltaFix);
// ClOrdID=PARENT-AAPL-2|OrdStatus=Filled|LeavesQty=0|

String back = conv.partialNvfixToFix(deltaNvfix);
// 11=PARENT-AAPL-2|39=2|151=0|
```

`fixToNvfix` / `partialFixToNvfix` share the same conversion (present fields
only). Use the `partial*` methods at call sites that publish deltas so the
intent is obvious.

## Build a delta (FIX or NVFIX)

`DeltaPublishBuilder` constructs that same partial payload field-by-field.
Keys can be FIX tags (`11`, `"39"`) or NVFIX names (`"OrdStatus"`) on the
same builder; values can be codes (`"2"`) or meanings (`"Filled"`). Only
fields that were set are emitted — no session/header/body fillers.

`set` and `get` both accept a tag number or a field name, whether the stored
fields came from FIX or NVFIX. `get` returns `Optional.empty()` when the field
is absent (the last value if a repeating tag appears more than once; use
`getAll` for every occurrence).

```java
String nvfix = new DeltaPublishBuilder(dict)
    .set(11, "PARENT-AAPL-2")
    .set("OrdStatus", "Filled")
    .set("LeavesQty", "0")
    .buildNvfix();
String fix = new DeltaPublishBuilder(dict)
    .set("ClOrdID", "PARENT-AAPL-2")
    .set(39, "2")
    .buildFix();

DeltaPublishBuilder delta = new DeltaPublishBuilder(dict).set(39, "2");
delta.get(39);          // Optional.of("2")
delta.get("OrdStatus"); // Optional.of("2") — same field

DeltaPublishBuilder parsedFix = DeltaPublishBuilder.fromFix(dict, fix);
parsedFix.get("OrdStatus"); // Optional.of("2") from a FIX payload
DeltaPublishBuilder parsedNv = DeltaPublishBuilder.fromNvfix(dict, nvfix);
parsedNv.get(39);           // Optional.of("Filled") from an NVFIX payload
parsedNv.get("ClOrdID");    // Optional.of("PARENT-AAPL-2")
```

Insertion order is the AMPS field order (including repeating groups: count
field, then members). Optional nested `group("NoAllocs", 1).set(...).end()`
is the same sequence with a count field written for you.

Pass an existing `FixNvfixConverter` as the second constructor argument if
you already have one; `buildNvfix()` uses it after encoding to FIX.

## Repeating groups

If the dictionary declares a group (`<group name="NoAllocs">` …), instances are
parsed using the group's delimiter field and written back in the same order.
Group members are not dropped on a round-trip when the XML defines the group.

## Unknown-field policy: `PASSTHROUGH` (default)

Documented and tested:

| input | output |
| --- | --- |
| tag not in the XML (e.g. `9001=EVT-1`, `9999=x`) | same numeric key on NVFIX (`9001=EVT-1`) |
| NVFIX name not in the XML | that name is kept as the FIX key so a round-trip restores it |
| enumerated field with an unknown code (`39=Z`) | value copied unchanged (`OrdStatus=Z`) |
| enum already a code on NVFIX (`OrdStatus=2`) | encoded as that code (`39=2`) |

Fields are never dropped because they are unknown. That covers AMPS custom tags
in the user-defined range and venue-specific extras on a delta.

## Public API

- `QuickFixDictionary.fromPath(Path)`
- `QuickFixDictionary.fromInputStream(InputStream)`
- `QuickFixDictionary.fromXml(String)`
- `FixNvfixConverter(QuickFixDictionary)`
- `FixNvfixConverter(QuickFixDictionary, UnknownFieldPolicy)`
- `String fixToNvfix(String fixMessage)`
- `String nvfixToFix(String nvfixMessage)`
- `String partialFixToNvfix(String fixFragment)`
- `String partialNvfixToFix(String nvfixFragment)`
- `static String printable(String payload)`
- `DeltaPublishBuilder(QuickFixDictionary)`
- `DeltaPublishBuilder(QuickFixDictionary, FixNvfixConverter)`
- `static DeltaPublishBuilder fromFix(QuickFixDictionary, String)`
- `static DeltaPublishBuilder fromNvfix(QuickFixDictionary, String)`
- `DeltaPublishBuilder set(int tag, String value)`
- `DeltaPublishBuilder set(String tagOrName, String value)`
- `Optional<String> get(int tag)` / `Optional<String> get(String tagOrName)`
- `List<String> getAll(int tag)` / `List<String> getAll(String tagOrName)`
- `GroupInstance group(int countTag, int count)` / `group(String countTagOrName, int count)`
- `String buildFix()` / `String buildNvfix()`

`UnknownFieldPolicy` currently has a single value, `PASSTHROUGH`.

## Tests

Unit tests load `src/test/resources/fix/FIX42-fixture.xml` (a trimmed
QuickFIX/J-shaped FIX 4.2 dictionary covering fields, enums, a message-level
group, and a component-level group). No live AMPS.
