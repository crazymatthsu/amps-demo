# Reconciling parent and child CumQty inside AMPS

The question, asked once the exposure views of [08](08-exposure-views.md)
were in place: *can a query against AMPS find any discrepancy between the
parent-level and child-level aggregated CumQty, by account, symbol and side,
for reconciliation?*

Yes, two ways. The first needs nothing new and gives a snapshot. The second
is a third view that joins the two exposure views and keeps the delta live,
so a break is a row in a topic rather than the output of a script. Both were
run on 2026-09-15 against `localhost/amps-demo:5.3.5.135` on a throwaway
container, with the scripted `MockFixFlow` published and then one deliberate
break injected.

## 1. Snapshot and diff outside AMPS

`view/fix42/exposure/parent` and `view/fix42/exposure/child` already carry
`Account`, `Symbol`, `Side` and `CumQty` per row. Snapshot both and join on
the three keys in jq, a spreadsheet, or a script:

```bash
./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/json --topic view/fix42/exposure/parent --mode snapshot"
./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/json --topic view/fix42/exposure/child --mode snapshot"
```

Adequate for an end-of-day check. Its limits are the reasons for §2: it is a
point-in-time picture, and the comparison logic lives in whichever tool did
the join.

## 2. A reconciliation join view

AMPS views may use other views as underlying topics, and a multi-topic
aggregation joins them on field equality. A third view over the two exposure
views therefore computes the delta server-side and is itself a SOW topic:
queryable, subscribable, and updated as either side moves.

```xml
<View>
    <Name>view/fix42/recon/parent_vs_child</Name>
    <MessageType>json</MessageType>
    <UnderlyingTopic>
        <Join>[view/fix42/exposure/parent]./Account = [view/fix42/exposure/child]./Account</Join>
        <Join>[view/fix42/exposure/parent]./Symbol = [view/fix42/exposure/child]./Symbol</Join>
        <Join>[view/fix42/exposure/parent]./Side = [view/fix42/exposure/child]./Side</Join>
    </UnderlyingTopic>
    <Projection>
        <Field>[view/fix42/exposure/parent]./Account AS /Account</Field>
        <Field>[view/fix42/exposure/parent]./Symbol AS /Symbol</Field>
        <Field>[view/fix42/exposure/parent]./Side AS /Side</Field>
        <Field>[view/fix42/exposure/parent]./Orders AS /ParentOrders</Field>
        <Field>[view/fix42/exposure/child]./Orders AS /ChildOrders</Field>
        <Field>[view/fix42/exposure/parent]./CumQty AS /ParentCumQty</Field>
        <Field>[view/fix42/exposure/child]./CumQty AS /ChildCumQty</Field>
        <Field>[view/fix42/exposure/parent]./CumQty - [view/fix42/exposure/child]./CumQty AS /CumQtyDelta</Field>
        <Field>[view/fix42/exposure/parent]./LeavesQty AS /ParentLeavesQty</Field>
        <Field>[view/fix42/exposure/child]./LeavesQty AS /ChildLeavesQty</Field>
        <Field>[view/fix42/exposure/parent]./LeavesQty - [view/fix42/exposure/child]./LeavesQty AS /LeavesQtyDelta</Field>
    </Projection>
    <Grouping>
        <Field>[view/fix42/exposure/parent]./Account</Field>
        <Field>[view/fix42/exposure/parent]./Symbol</Field>
        <Field>[view/fix42/exposure/parent]./Side</Field>
    </Grouping>
</View>
```

Points of syntax and placement, each of which cost a validation run:

- **Declare it after the two exposure views.** A join names topics that must
  already be defined earlier in the config; `ampServer --verify-config`
  rejects the reverse order.
- **One `<Join>` element per key field.** Several joins are ANDed. Values are
  compared as strings, which is what `Side` (`"1"` / `"2"`) and the other
  two keys are.
- **No message-type qualifier is needed** because the view and both
  underlying views are json. A view over topics of a *different* type needs
  the `[type].[topic]` form recorded in [08](08-exposure-views.md).
- **The expressions stay bare.** Nothing is wrapped in `IF()`, for the reason
  measured in 08: on this build an aggregate nested in `IF()` accumulates
  add-only on live updates.

### 2.1 Two properties of join views that shape the query

**The join is a left outer join from the first topic named.** A parent group
with no child slices still produces a row, with JSON `null` in every child
column and in the deltas. That is a "not sliced" row, not a break, so the
break condition has to exclude it.

**A join view cannot carry a `<Filter>` element.** The AMPS user guide states
this for multiple-topic aggregation, so the break condition lives on the
read side: a content-filtered SOW query, or a filtered subscription.

```bash
./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/json --topic view/fix42/recon/parent_vs_child --mode query --filter \"/ChildCumQty IS NOT NULL AND /CumQtyDelta != 0\""
```

The same filter on a `sow_and_subscribe` with out-of-focus notifications
gives a live breaks blotter: a row appears when the two levels diverge and
falls out again, with an OOF message, when they reconcile.

### 2.2 What the run showed

Full snapshot after the mock flow, seven rows, one per parent group:

| Account | Symbol | Side | ParentCumQty | ChildCumQty | CumQtyDelta |
| --- | --- | --- | ---: | ---: | ---: |
| ACC-INSTL-01 | AAPL | 1 | 12000 | null | null |
| ACC-INSTL-01 | MSFT | 2 | 1500 | null | null |
| ACC-HEDGE-07 | GOOG | 1 | 0 | null | null |
| ACC-HEDGE-07 | NVDA | 1 | 1000 | null | null |
| ACC-INSTL-02 | TSLA | 1 | 16000 | 16000 | 0 |
| ACC-INSTL-01 | AMZN | 1 | 1000 | null | null |
| ACC-HEDGE-07 | META | 2 | 3000 | null | null |

The breaks query returned nothing: TSLA, the only sliced parent, reconciles.

A break was then injected as a single `delta_publish` to `sow/fix42/orders`
on the fix connection, an extra fill on child slice B that the parent never
reported (fields SOH-separated on the wire):

```
35=8|11=CHILD-TSLA-B-2|37=ORD-CHILD-TSLA-B|17=EXEC-CHILD-TSLA-B-99|39=1|150=1|
60=20260821-13:40:00.000|38=8000|14=5000|151=3000|6=242.1|32=1000|31=242.1
```

Tag 11 names a ClOrdID the chaining module already knows, so the message
lands on slice B's record; its CumQty moves from 4000 to 5000 and the child
view from 16000 to 17000. The breaks query then returned exactly one row:

| Account | Symbol | Side | ParentCumQty | ChildCumQty | CumQtyDelta | LeavesQtyDelta |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| ACC-INSTL-02 | TSLA | 1 | 16000 | 17000 | -1000 | -3000 |

No restart between the two queries: the join view followed the child view,
which followed the blotter, on the live path.

## 3. The key: when account × symbol × side is enough

The three-field key reconciles correctly only when **every parent in a group
is sliced**. If an unsliced parent shares an account, symbol and side with a
sliced one, the parent level legitimately exceeds the child level and the
view reports a break that is not one. The mock has no such group, which is
why §2.2 is clean, and a real desk may.

The sharper reconciliation is **per parent ClOrdID**: the existing
`view/fix42/exposure/children_by_parent` row, keyed by tag 9000, compared
with the parent's own blotter record. Two things stand in the way of writing
that as a join today:

- tag 9000 on a child names the parent's *original* ClOrdID, while the
  parent's blotter record carries its *latest* tag 11, which rotates on every
  amend. The join key exists on the child side only;
- the fix is small but is a publisher change, not a view: copy tag 11 into a
  root-ClOrdID tag (say 9015) in the `new-order` route only, so later deltas
  never touch it, then join `children_by_parent./ParentOrderID` to
  `[fix].[sow/fix42/orders]./9015`.

Until then, the per-parent comparison is what
`ExposureViewIT.childrenByParentAgreesWithTheParentRecord` does from the
client side, for the one sliced parent in the mock.

## 4. Where this stands

The view is in the repository: crazymatthsu/amps-demo#27 added
`view/fix42/recon/parent_vs_child` to
[`amps-config.xml`](../../server/config/flows/fix42-chaining/amps-config.xml)
after the three exposure views, with the projection of §2, and
[`ReconViewIT`](../../fix42-publisher/src/integrationTest/java/com/demo/amps/fix42/it/ReconViewIT.java)
pins both halves of §2.2 live and without a restart: the scripted flow
reconciles, and a child fill the parent never mirrors surfaces as exactly one
break. The join section of
[08](08-exposure-views.md#reconciling-the-two-levels-viewfix42reconparent_vs_child)
is that change's record; this note is the analysis behind it. The per-parent
key of §3 remains open.
