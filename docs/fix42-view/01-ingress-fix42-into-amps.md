# Sending FIX 4.2 messages into AMPS topics `algo/D` … `algo/9`

**Yes — this part is native AMPS territory.** AMPS parses raw FIX as a
first-class message type, topics spring into existence on first publish, and a
single regex subscription covers the whole family. The design work is not
*whether* it can be done but three choices: the wire encoding, whether the
ingress topics are SOW-keyed, and how total ordering across five topics is
preserved. This document settles all three.

Scope note: the docs/fix42 contract
([01-fix42-messages-and-state-machine.md](../fix42/01-fix42-messages-and-state-machine.md))
includes **35=Q DontKnowTrade** alongside D/G/F/8/9. The question named five
topics; plan for six — `algo/Q` costs one config line now and a migration later.

## 1. Encoding: carry the FIX natively

Three options, all demonstrated in this repository
([native-fix-and-nvfix.md](../src/native-fix-and-nvfix.md)):

| | `fix` (raw tag=value) | `nvfix` (named fields) | JSON (normalized) |
| --- | --- | --- | --- |
| server keys/filters on fields | yes: `/35`, `/11`, `/37` | yes: `/MsgType` | yes: `/clOrdId` |
| gateway work at ingress | none — forward as-is | rename tags | full translation |
| wire-faithful audit trail | yes | no (renamed) | no (restructured) |
| human-readable in admin console | no | mostly | yes |

For the **ingress** topics the recommendation is `MessageType fix`, forwarded
as-is: the feed is an audit/drop-copy view (contract §1 even tolerates stripped
framing — the parser records `ChecksumOk` rather than rejecting), and an audit
trail is worth the most when nothing rewrote it. The lenient-parse requirement
lives in the state machine, not in AMPS: AMPS shreds whatever tags are present
and never enforces FIX session semantics, which is exactly the tolerance the
contract asks for.

The *derived* state topics that come out of the state machine are a different
decision — JSON, for readability, nested delta merge, and the admin SQL
console — covered in [03-proposed-architecture.md](03-proposed-architecture.md).

## 2. Topic declarations: journal is mandatory, SOW is optional

The five (six) `algo/*` topics carry **events, not state**. Two consequences:

**They must be journalled.** The ingress stream is the system of record: the
state machine's output is derivable from it by replay, its ExecID-dedupe set is
rebuilt from it, and a rules change is deployed by replaying it through a new
machine. Add every `algo/*` topic to `<TransactionLog>`. (This is the same
reasoning as `fix.events` in
[the default flow's amps-config.xml](../../server/config/flows/default/amps-config.xml) and
[transaction-log-sizing.md](../src/transaction-log-sizing.md): journal the
stream you replay, not the state you derive.)

**They do not need to be SOW topics.** A plain journalled topic fully supports
publish, subscribe, and bookmark replay — everything the pipeline needs.
Declaring them as SOW topics adds one thing: a *queryable event log* ("show me
that G again" without a replay). If you want that, the keys write themselves
from the contract's tag vocabulary (§2: tag 11 is a **new value on every
D/G/F**, tag 17 is **unique per 8**):

| topic | messages | SOW key | what one record then means |
| --- | --- | --- | --- |
| `algo/D` | NewOrderSingle | `/11` | one record per order request |
| `algo/G` | OrderCancelReplaceRequest | `/11` | one record per amend request |
| `algo/F` | OrderCancelRequest | `/11` | one record per cancel request |
| `algo/8` | ExecutionReport | `/17` | one record per execution report |
| `algo/9` | OrderCancelReject | `/11` | latest reject per rejected request |
| `algo/Q` | DontKnowTrade | `/17` | latest DK per referenced exec |

Every key tag is required on its message type, so no publish fails for a
missing key (a SOW publish without its key field is rejected — the lesson the
`fix.native.orders` topic already encodes). Two caveats worth stating:

- **SOW dedupe is not replay dedupe.** A PossDup resend of an 8 with the same
  ExecID collapses to one *stored* record under `/17`, but the journal keeps
  both copies and a bookmark replay delivers both. The ExecID dedupe rule
  (contract §5 rule 2) stays in the state machine regardless.
- **Do not key `algo/8` on `/37`** at ingress. That turns the topic into
  "last-arrived report per order" — it discards fills history and lets a
  stale or out-of-order report clobber a newer one, which is precisely the
  arbitration a SOW cannot do (see
  [02-amps-view-feasibility.md](02-amps-view-feasibility.md) §3.2).

Illustrative declarations (not applied anywhere yet):

```xml
<SOW>
  <Topic>
    <Name>algo/D</Name>
    <MessageType>fix</MessageType>
    <Key>/11</Key>
    <FileName>./sow/%n.sow</FileName>
    <Durability>persistent</Durability>
  </Topic>
  <!-- algo/G, algo/F, algo/9 identical but for Name; algo/8 and algo/Q keyed /17 -->
</SOW>

<TransactionLog>
  <Topic><Name>algo/D</Name><MessageType>fix</MessageType></Topic>
  <Topic><Name>algo/G</Name><MessageType>fix</MessageType></Topic>
  <Topic><Name>algo/F</Name><MessageType>fix</MessageType></Topic>
  <Topic><Name>algo/8</Name><MessageType>fix</MessageType></Topic>
  <Topic><Name>algo/9</Name><MessageType>fix</MessageType></Topic>
  <Topic><Name>algo/Q</Name><MessageType>fix</MessageType></Topic>
</TransactionLog>
```

**An optional seventh topic: the chained blotter.** AMPS's optional chaining
key generator module can key a SOW topic on the transitive 41→11 chain — one
record per order chain, computed server-side. If adopted (the full analysis,
including why it must not replace the journalled ingress topics, is
[02-amps-view-feasibility.md](02-amps-view-feasibility.md) §4), it is an
*additional* topic the gateway delta-publishes every message into, e.g.
`algo/chain` with `<KeyGenerator><Module>key-chaining</Module>` and keys
`/11`, `/41` (candidates `/37`, `/17` — verify). It cannot be one of the six
per-type topics: the generator chains only within a single topic, so D and G
split across topics never meet.

A `<Pattern>^algo/(D|G|F|8|9|Q)$</Pattern>` dynamic-SOW family would be more
compact, but a pattern topic shares **one** `<Key>` across everything it
captures — and no single tag serves: `/11` is right for requests but wrong for
8s (many 8s share a ClOrdID; last-value would eat fills), `/17` does not exist
on requests. Six explicit declarations are the correct shape here. (And if you
do reach for a pattern anywhere, the regex goes in `<Pattern>`, never `<Name>` —
the silent-mismatch trap verified in this repo's
[VERIFICATION.md](../../VERIFICATION.md).)

## 3. Ordering across five topics — the one real subtlety

The state machine is order-sensitive: D before its 8s, a G before the 9 that
rejects it. Splitting ingress across five topics raises the question of whether
that order survives. Within a single AMPS instance it does, twice over:

- **Live:** one client subscription covering the family (topic regex
  `^algo/(D|G|F|8|9|Q)$`, or six subscriptions merged — prefer the regex, one
  stream, server-ordered) delivers messages in the order the instance processed
  them, regardless of topic.
- **Replay:** the transaction log is one ordered journal across all logged
  topics, so a bookmark subscription over the same regex replays the family in
  original publish order. This is the property the whole recovery story leans
  on.

What the broker cannot repair is **publisher-side order**: AMPS orders by
arrival. The gateway must publish each FIX session's messages sequentially over
a single connection (per-session order is all the contract needs — chains do
not span sessions). Publishing D/G/F/8/9 from concurrent connections would
interleave arbitrarily, and no downstream logic can reconstruct what the wire
order was.

**The honest alternative:** a single topic `algo/fix` carrying all six types
makes ordering a non-question, and AMPS content filters give subscribers the
same selectivity server-side (`/35 = '8'` on a subscription is evaluated in the
broker, exactly like subscribing to `algo/8`). The per-type split is a naming
convenience — legitimate (per-type ACLs, per-type stats in the admin console,
cheaper than filter evaluation), but a convenience, not a requirement. Either
layout supports everything in
[03-proposed-architecture.md](03-proposed-architecture.md); the analysis
proceeds with the five-plus-one topics as asked.

The chaining key generator tilts this trade further toward one topic: chain
collapse works only among messages of a single topic, so if the chained
blotter is wanted, single-topic ingress gets it by adding one `<KeyGenerator>`
element, while the per-type layout needs a seventh, everything-again topic
(§2). Ordering plus chaining is two arguments for `algo/fix` against the
split's naming convenience.

## 4. Verify on your build

House rule ([README](../../README.md)): claims about config behaviour get
re-checked against a live instance. One-second checks, in order:

1. `./server/scripts/amps.sh validate` after adding the declarations — in
   particular tag-number key syntax (`/11`, `/17`) on `MessageType fix` topics
   (expected form per the native-FIX demos, flagged for verification there
   too).
2. Topic **regex in a bookmark subscription** over the `algo/*` family replays
   in journal order — exercise once with the `bookmark-replay` demo pattern
   before relying on it.
3. FIX field values are text: equality filters (`/35 = '8'`) are safe; confirm
   numeric-comparison behaviour before writing a range filter like `/14 > 0`
   (same caveat as [native-fix-and-nvfix.md](../src/native-fix-and-nvfix.md)).
4. Whether your AMPS version accepts `/` in topic names uniformly across SOW,
   TransactionLog, and admin tooling — this repo's topics all use `.`;
   `algo/D` is expected to work (AMPS topic names are strings and the docs use
   slashes in examples), but nothing here has run it. If it surprises,
   `algo.D` changes nothing else in this analysis.
5. If the chained blotter is adopted: that
   `libamps_id_chaining_key_generator.so` ships in your AMPS tarball and loads
   via `<Module>`, that `validate` accepts the `<KeyGenerator>` element, and
   the module's behaviour on a message missing the primary key field (35=Q has
   no tag 11) — undocumented, so test it — plus the two-chains error path
   ([02-amps-view-feasibility.md](02-amps-view-feasibility.md) §4.3).
