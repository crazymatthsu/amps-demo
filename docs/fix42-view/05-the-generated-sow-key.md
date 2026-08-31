# The generated SOW key, from the subscriber's side

When the chaining key generator keys a topic, the key it computes is not any
field in the message. This document records what a consumer can do with it,
measured against AMPS 5.3.5.135.

The question: **`sow/parent/orders` is keyed by the module on tags 11 and 41 —
can a subscriber get the generated key through the API?**

Yes, on every delivery, via `Message.getSowKey()`. Two of the properties below
are stronger than the module's documentation promises, which is why they are
pinned as tests rather than trusted: `SowKeyIT`.

---

## 1. Why this matters

The publisher deliberately knows nothing about chain identity. It sends tags 11
and 41 and lets the server resolve the record — that is the whole point of
[delegating identity to the module](02-amps-view-feasibility.md#4-the-chaining-key-generator-the-blocker-that-falls).

A consumer is in a different position. It receives a stream in which one order
appears under three, four, five different ClOrdIDs, and needs to know they are
one order. Without the generated key it would have to rebuild the 41→11 closure
client-side — reintroducing exactly the state this design removed, on every
consumer rather than one publisher.

The key removes that. It is the server saying "these messages are the same
order", in a field the consumer gets for free.

---

## 2. What is available

| property | result |
| --- | --- |
| present on `sow` query | yes |
| present on `sow_and_subscribe` | yes |
| present on plain `subscribe` | yes |
| present on `sow_and_delta_subscribe` | yes |
| identical across a chain's messages | yes |
| assigned at the chain root | yes — the first message already carries the final key |
| usable to query the record back | yes, `Command.setSowKeys(key)` |
| distinct chains, distinct keys | yes |
| survives a server restart | yes |
| identical on a different instance | **yes — it is deterministic** |

A live subscription to a three-message chain, tags 11 differing on each:

```
subscribe   sowKey=13877596816621223565  11=P1   35=D
subscribe   sowKey=13877596816621223565  11=P2   35=G
subscribe   sowKey=13877596816621223565  11=P3   35=F
```

The delta subscriber sees the same key on the same deliveries. That case is the
one worth noticing: a delta subscriber receives only the fields that changed, so
a payload can arrive carrying almost nothing — and the key still says which
record it belongs to.

---

## 3. Determinism, which is stronger than persistence

Persistence is what the module's `<FileName>` option is for, and it works: after
`amps.sh restart`, with the SOW file and chain map intact, the key is unchanged.

The stronger result was not expected. Publishing the *same chain* into a
completely fresh instance — new container, empty data directory, no chain map,
no journal — produces the **same key**:

| | key |
| --- | --- |
| original chain (K1 → K2 → K3) | `7922759751103120723` |
| after restart, data preserved | `7922759751103120723` |
| after a full wipe, chain republished | `7922759751103120723` |
| on a second, independent instance | `7922759751103120723` |

So the key is a deterministic function of the chain's root identifier, not a
counter and not instance-local. Practical consequences:

- two AMPS instances fed the same order flow agree on keys, so it works as a
  **correlation id across systems**, not merely a local handle;
- a replayed feed reproduces its keys, so a rebuilt SOW is comparable to the
  original record for record;
- it is safe to store the key alongside data in another system, which would not
  be true of a counter.

**Caveat, and it is not a small one:** none of this is documented by 60East as a
contract. It is an observed property of one build. `SowKeyIT` asserts it so an
upgrade that changed it fails loudly here rather than silently in a consumer.

---

## 4. Treat the key as an opaque string

The key renders as decimal digits, which invites parsing it as a number. Do not.

It is an **unsigned** 64-bit value. One key observed in an ordinary run was
`13877596816621223565`, which is larger than `Long.MAX_VALUE`
(`9223372036854775807`) — so `Long.parseLong` on it throws
`NumberFormatException`. Whether a given key overflows depends on where the hash
lands, so the failure is data-dependent: it will pass every test until the day a
particular order chain hashes high.

Carry it as a `String` end to end. `BigInteger` works if a numeric type is
genuinely needed; a signed `long` does not.

---

## 5. What it does not give you

The key identifies the chain. It says nothing about the order's state, and it is
not a substitute for the fields that do:

- it does not tell you which ClOrdID is currently working — that is tag 9014
  ([04](04-pending-state-without-a-state-machine.md));
- it is not an OrderID. Tag 37 is the venue's identifier and exists only after
  the first execution report; the generated key exists from the first message,
  including before any ack;
- it is per topic. The key for a chain on `sow/parent/orders` has no relation to
  anything on `sow/parent/execs`, which is keyed on tag 37 by an ordinary SOW
  key, not by the module.

And the module's own hard rule still applies upstream of all this: **tags 11 and
41 must reach it unmodified**. A publisher that rewrites either hides the chain
linkage, the module opens a second chain, and the order silently becomes two
records with two keys — the failure described in
[04, §3](04-pending-state-without-a-state-machine.md#3-the-constraint-that-cost-a-rewrite).

---

## 6. Where this is pinned

[`SowKeyIT`](../../fix42-publisher/src/integrationTest/java/com/demo/amps/fix42/it/SowKeyIT.java),
seven tests against a real instance: presence on all four command forms, one key
per chain, root assignment, query-by-key, distinct chains, restart survival,
cross-instance determinism, and the opaque-token rule.

Its restart case needed `AmpsTestServer.restart()`, which waits for a **new**
`initialization completed` marker rather than any marker — the previous run's is
still in the log, so matching on presence returns a server that is still
starting. The same test also rebuilds the suite's shared client afterwards: a
restart drops every connection, and a plain AMPS `Client` does not reconnect
itself, so leaving it dead surfaces as a `DisconnectedException` in whichever
unrelated test JUnit happens to run next.
