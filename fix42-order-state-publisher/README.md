# fix42-order-state-publisher

**Being built, phase by phase.** The architecture and the implementation plan
are in [`docs/`](docs/README.md); read that first. Built so far, per
[docs/05](docs/05-implementation-plan.md): phase 0 (the module, its own copy of
the FIX layer and the mock venue, pinned to `fix42-publisher`'s scripted flow)
and phase 1 (a mock venue with several requests in flight, fourteen dialect
variants, and `EdgeCaseFlow`: the docs/06 rows as scripts).

The stateful successor to [`fix42-publisher`](../fix42-publisher/README.md).
Same FIX 4.2 drop-copy flow, same audit topics, same full publish on `35=D`
and delta publish for everything after — but the order blotter is keyed by the
**publisher**, from a small in-memory cache of ids, acked terms, venue status,
cumulative quantities and the *set* of requests in flight, instead of by the
AMPS chaining key generator module.

The case that motivates it: two cancels for one order, the venue confirms one
and then rejects the other. The stateless publisher's last-writer-wins merge
leaves that order reading as cancel-rejected; this one leaves it canceled,
whichever answer arrives first
([docs/01](docs/01-problem-the-two-cancels.md), [docs/03 §7.1](docs/03-state-machine.md#71-two-cancels-confirm-then-reject-the-motivating-case)).

```bash
# once built, per docs/05:
export AMPS_IMAGE=localhost/amps-demo:5.3.5.135
AMPS_FLOW=fix42-order-state ./server/scripts/amps.sh start
./gradlew :fix42-order-state-publisher:bootRun
```

| document | what it covers |
| --- | --- |
| [docs/01](docs/01-problem-the-two-cancels.md) | the failure, traced through the shipped rulebook; the class of edge cases it belongs to |
| [docs/02](docs/02-architecture.md) | topology, the cache, the record schema, publish modes, server flow, configuration |
| [docs/03](docs/03-state-machine.md) | the rules, and eleven walkthroughs showing the record after every message |
| [docs/04](docs/04-recovery-and-operations.md) | restart, cache misses, eviction, single writer, metrics, failure matrix |
| [docs/05](docs/05-implementation-plan.md) | six phases, class layout, open decisions, risks |
| [docs/06](docs/06-case-coverage.md) | the case matrix: every lifecycle and edge case, the rule that handles it, and a verdict |
| [docs/07](docs/07-extension-points.md) | policies per venue, the hook SPI, replaceable components |
| [docs/08](docs/08-review-findings.md) | round-1 review findings, the six decisions taken on them, where each landed |
| [docs/09](docs/09-review-round-2.md) | round-2 review of the revised docs: round-1 dispositions verified, twenty-two new findings and where each landed |
| [docs/10](docs/10-case-frequency-and-priority.md) | every case sorted by frequency (normal, sometimes, rare) and build slot, for implementation order |
