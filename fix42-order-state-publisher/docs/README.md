# fix42-order-state-publisher — design notes

The design worked out before the code. `fix42-order-state-publisher` is the
stateful successor to [`fix42-publisher`](../../fix42-publisher/README.md):
the same FIX 4.2 drop-copy flow, the same audit topics, the same
full-publish-on-`35=D` / delta-publish-everywhere-else shape — but the order
blotter is keyed by the **publisher**, from a small in-memory cache, instead of
by the AMPS chaining key generator module. That cache is what lets it apply a
message *conditionally*, which is the one thing a server-side merge can never
do, and the reason the stateless design shows a cancelled order as
"cancel rejected" when two cancels race.

Read in order for the argument; jump to [05](05-implementation-plan.md) for
the phases.

| # | document | what it settles |
| --- | --- | --- |
| [01](01-problem-the-two-cancels.md) | The problem | the two-cancels failure traced message by message against the shipped rulebook; the class of failure it belongs to (last-writer-wins where the contract needs conditional apply); the other edge cases in the same class; what the stateless design gets right and must be kept |
| [02](02-architecture.md) | The architecture | topology, components, the cache (what is in it, what is deliberately not, how big it is), the blotter record's tag schema, the publish modes, the server flow, the configuration surface, the threading model, and what changes for a consumer of the old blotter |
| [03](03-state-machine.md) | The state machine | identity resolution, the pending-request *set*, the status rule (venue truth, with one policy decision on `35=9`), arbitration (duplicate, stale, terminal absorption), and the transition rules per message type — then the two-cancels case and nine more edge cases walked through as the record a subscriber would read after each message |
| [04](04-recovery-and-operations.md) | Recovery and operations | why the SOW record is the checkpoint and the cache is only an accelerator; restart by SOW rebuild vs journal replay; cache misses and eviction; the single-writer rule; metrics; the failure matrix |
| [05](05-implementation-plan.md) | The implementation plan | six phases with deliverables, tests and exit criteria; the class layout; the decisions still open, each with a recommendation |
| [06](06-case-coverage.md) | Case coverage | fifty-odd lifecycle and edge cases — normal flows, requests racing their answers, reports racing each other, mid-stream and identity — each with the rule that handles it and a verdict; and the three rules the matrix tightened |
| [07](07-extension-points.md) | Extension points | per-venue policy bundles, the `OrderStateHook` SPI (what a hook may and must not do), replaceable components, and what is deliberately not extensible |
| [08](08-review-findings.md) | Review findings, round 1 | an independent adversarial review of the first version of 01–07: twenty-two findings with the message sequence, what the docs said, what went wrong and a disposition; the six decisions taken on them; and where each finding landed in the revised documents |
| [10](10-case-frequency-and-priority.md) | Case frequency and priority | every matrix row sorted into normal, sometimes and rare, each with a build slot (first cut, before production, after); the rare rows that go first anyway, and the frequent ones that can wait |
| [09](09-review-round-2.md) | Review findings, round 2 | the revised documents reviewed again: the status of every round-1 disposition as it actually landed; twenty-two new findings, three of them serious — the collision rule unreachable behind a single id map, a publish-failure repair that would erase a record's terms, a topic outside the design's own journal rule — with dispositions and where each landed |
| [11](11-build-findings.md) | Build findings | what executing the rules found that reading them did not -- the late guard shadowing two after-terminal rows (F1), a pending ack's 39 outliving its request (F2, closed by a shipped hook for the venue that needs it), whole-second TransactTime (F3) -- and what AMPS 5.3.5.135 does that the design assumed otherwise: a confirm overtaken by a later reject on a merged tap (F4, open), keyless payloads filed under one empty-key record rather than refused (F5), a second same-name logon forcing the first off rather than being refused (F6, open); an audit copy's own 13011 misleading the miss lookup (F7) and lookups that must run in resolution order (F8); the phase 5 measurements and what they found, a long chain's id list copied on every message (F9, open), a tombstone's real size and lifetime (F10), eviction without the miss lookup overwriting the record (F11, now refused at startup) and loaded orders never scheduled for eviction (F12, now scheduled at load); every verify-on-build answer; and what is not built yet |

## The design in one paragraph

Every message resolves to a **root ClOrdID** (the tag 11 of the chain's
`35=D`; for a chain first seen mid-stream, the ClOrdID closest to the root its
first message carries — always one of the drop copy's own ids) through a
publisher-owned index of every ClOrdID and OrderID ever seen. The key is
written once, in the D's full publish, as tag 13011, and is the plain SOW key
of `sow/fix42/order_state`, so the server needs no module. The record's other
custom tags follow one scheme: 15xxx is the pending value of standard tag xxx
(15011, 15035, 15038, 15044 for the request in flight), 13xxx a root value,
9000–9099 the machine's bookkeeping. A cache entry per order holds the ids (11, 41, 37, the
working id, the chain), the acked terms (38, 44), the venue's last status and
cumulative snapshot (39, 14, 151, 6), the last ExecID, and a *set* of
in-flight requests — each with its own ClOrdID, action and proposed terms.
`35=D` creates the entry and is published whole; every other message is
applied to the entry under the rules in [03](03-state-machine.md) and the
**difference** is delta-published, through an AMPS client with a publish
store so that nothing is lost or left unreported between the feed and the
blotter. Because the venue's status is written only by the venue's own
reports, and a `35=9` only removes its request from the set, a cancel
confirmed by one report and a second cancel rejected afterwards end as
`39=4`, `15035=NONE`: canceled, with nothing pending. The record carries
everything the cache knows, so a restart rebuilds the cache from one `sow`
query; a cache miss is resolved through the audit topics, which carry the key
too, before it ever creates a second record.

## What is and is not verified

These notes are a design. Nothing in them has yet run against an AMPS
instance. Where a statement about AMPS behaviour is load-bearing it is
labelled **verified** if the existing `fix42-publisher` suite already pins it
(for instance that a delta merge preserves untouched fields, and that `151=0`
stamped on a terminal report zeros the exposure views), and **verify on
build** if this module's integration tests will be the first to exercise it
(for instance that a `delta_publish` whose key has no record is stored as a
publish, which [04](04-recovery-and-operations.md) relies on for the
cache-miss path but [02](02-architecture.md) does not depend on). The
measured numbers quoted — publish throughput, allocation per message — are
`fix42-publisher`'s own, from
[docs/fix42-view/06](../../docs/fix42-view/06-publish-throughput.md), and are
the baseline this module is held to in phase 5.
