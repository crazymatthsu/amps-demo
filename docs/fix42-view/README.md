# FIX 4.2 order state on AMPS: topics `algo/*`, views, and the state machine

Analysis only — nothing here is implemented yet. The question under analysis:

> Can I publish FIX 4.2 messages (35=D, G, F, 8, 9) into AMPS topics
> (`algo/D`, `algo/G`, `algo/F`, `algo/8`, `algo/9`) and build an **AMPS view**
> that maintains the live latest state of every order — pending qty change,
> pending price change, acked qty, acked price, CumQty, AvgPx, order status —
> handling new/amend/cancel pending/ack/reject and fill bust/correct? If a view
> cannot do it, should the FIX 4.2 state machine live outside AMPS, and what is
> the architecture?

The state-machine semantics referenced throughout are the binding contract in
[docs/fix42/01-fix42-messages-and-state-machine.md](../fix42/01-fix42-messages-and-state-machine.md).

## Verdict in three sentences

1. **Ingress: yes.** AMPS accepts raw FIX 4.2 natively (`MessageType fix`), so
   the five `algo/*` topics are straightforward to declare, journal, and
   subscribe to — details and the one ordering subtlety in
   [01-ingress-fix42-into-amps.md](01-ingress-fix42-into-amps.md).
2. **A view alone: no.** Every construction that tries to derive the required
   order state inside AMPS — SOW keying, aggregated views, joined views — fails
   on at least one required behaviour, and four of the failures are structural,
   not syntactic: chain identity across replaces, latest-*valid* (not
   latest-arrived) report selection, pending-request correlation, and
   sequence-dependent status. The field-by-field analysis is
   [02-amps-view-feasibility.md](02-amps-view-feasibility.md).
3. **So: a thin state machine outside AMPS**, with AMPS as the journalled
   system of record on the way in and the queryable/subscribable state store on
   the way out — and views still earn their keep *above* the machine's output,
   for aggregations like exposure by account. The proposed architecture,
   recovery story, and the mapping onto the docs/fix42 contract are in
   [03-proposed-architecture.md](03-proposed-architecture.md).

This is the same conclusion this repository already reached for a smaller
version of the question ([docs/src/fix-order-state.md](../src/fix-order-state.md),
the `fix-lifecycle` demo, and
[FixOrderStateMachine](../../common/src/main/java/com/demo/amps/common/fix/FixOrderStateMachine.java)) —
the analysis here extends it to the fuller docs/fix42 contract (35=Q, busts and
corrects, per-request pending snapshots, the `executions` / `order_events`
output rows) and to the `algo/*` per-message-type topic layout.

## Documents

| document | answers |
| --- | --- |
| [01-ingress-fix42-into-amps.md](01-ingress-fix42-into-amps.md) | can 35=D/G/F/8/9 be published into `algo/D`…`algo/9`, and how should those topics be declared? |
| [02-amps-view-feasibility.md](02-amps-view-feasibility.md) | can an AMPS view maintain the live order state? (field-by-field, with the constructions that almost work) |
| [03-proposed-architecture.md](03-proposed-architecture.md) | the architecture with the state machine outside AMPS, mapped rule-by-rule to the docs/fix42 contract |
