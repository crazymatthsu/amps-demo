# Exposure views over the chained blotter: what worked, and the one trap

[02, §6](02-amps-view-feasibility.md#6-what-views-are-the-right-tool-for--above-the-machine-not-below-it)
put "exposure per account/symbol" on the list of things views are *for*, and
closed with "verify view syntax against your AMPS version". This note records
that verification, done on `localhost/amps-demo:5.3.5.135` by building the
views into the `fix42-chaining` flow and asserting them from an integration
test. Three findings are not in the vendor documentation and cost several
probe runs each; they are the reason the note exists.

## What was built

Four json-typed views in
[`amps-config.xml`](../../server/config/flows/fix42-chaining/amps-config.xml):
three over `sow/fix42/orders`, read back by
[`ExposureViewIT`](../../fix42-publisher/src/integrationTest/java/com/demo/amps/fix42/it/ExposureViewIT.java),
and a join of the first two, read back by
[`ReconViewIT`](../../fix42-publisher/src/integrationTest/java/com/demo/amps/fix42/it/ReconViewIT.java)
and described in [its own section](#reconciling-the-two-levels-viewfix42reconparent_vs_child) below:

| view | filter | grouped by | one row per |
| --- | --- | --- | --- |
| `view/fix42/exposure/parent` | `/9000 IS NULL` | `/1`, `/55`, `/54` | account × symbol × side, parent orders |
| `view/fix42/exposure/child` | `/9000 IS NOT NULL` | `/1`, `/55`, `/54` | account × symbol × side, child slices |
| `view/fix42/exposure/children_by_parent` | `/9000 IS NOT NULL` | `/9000` | parent ClOrdID: its slices rolled up |
| `view/fix42/recon/parent_vs_child` | none (a join takes no `<Filter>`) | `/Account`, `/Symbol`, `/Side` of the parent view | parent group: its totals beside its slices', and the difference |

Every row projects `/Orders` (`COUNT(/11)`), `/OrderQty` (`SUM(/38)`),
`/LeavesQty` (`SUM(/151)`), `/CumQty` (`SUM(/14)`) and `/AvgPx` as
`SUM(/14 * /6) / SUM(/14)`, the VWAP form the AMPS user guide's own view
examples use.

**Why the blotter and not the execs topics.** The chained record already
carries account (1), symbol (55), side (54) and ParentOrderID (9000) from the
`35=D` next to the venue's absolute CumQty (14), LeavesQty (151), AvgPx (6)
and OrderQty (38) from its reports. A trade bust or correct is therefore
absorbed at order level before a view ever sees it
([07](07-trade-busts-and-corrects.md)). `sow/fix42/execs` and `execs_audit`
carry no 1/55/54 at all, and a view over `execs_audit` would have to undo
busted ExecIDs itself.

**Why two levels.** Parents and children share the topic, and a child's fills
are reported to its parent as well, so the parent's 14/151 already contain
its slices. One view over the whole blotter would show the TSLA parent and its
two slices as three orders and 32000 filled. Splitting on tag 9000 puts every
record in exactly one of the two views; the third rolls the slices up under
their parent so a reader can compare that row with the parent's own record.

## Finding 1: never nest an aggregate inside `IF()`

The natural guard for the VWAP is

```
IF(SUM(/14) > 0, SUM(/14 * /6) / SUM(/14), 0) AS /AvgPx
```

On 5.3.5.135 that expression is wrong on the **live update path**: when a
record in the group changes, the old contribution is never subtracted, so the
value accumulates add-only and drifts further from the truth with every
fill. A restart rebuilds the view from the SOW and the number snaps to the
correct value, which is exactly what makes the fault hard to see: a test that
restarts before reading passes. The bare forms, a top-level `SUM(expr)` field
and the plain division, are correct both live and after a rebuild.

Two consequences. The shipped views use bare aggregates only. And
`ExposureViewIT` deliberately reads the views **without restarting the
container**, after each blotter record has been updated many times on its way
to its final state, because that is the only path on which a regression to
the guarded form shows.

## Finding 2: the guard was never needed

With no fills in a group the VWAP divides 0 by 0. A fix-typed view renders
that as `0`; a json-typed view renders it as `null` (the documentation's
"most message types default to 0 instead of NaN" is the fix case). Neither
raises, and `null` is the more honest answer for "nothing to average", which
is one of two reasons the views are json-typed. The other is that a json view
returns numbers as numbers, where a fix view returns text. Numbers of a
particular shape: a `SUM` over the fix topic's text values arrives as a
double (`"CumQty":16000.0`), `COUNT` as an integer, and the grouping fields
as the strings they were (`"Side":"1"`), which is why the test's reader goes
through `BigDecimal` for quantities rather than asking for a long.

## Finding 3: a cross-type view qualifies everything

A json view over a fix topic needs every reference fully qualified, in the
projection and the grouping alike:

```xml
<UnderlyingTopic>[fix].[sow/fix42/orders]</UnderlyingTopic>
<Projection>
    <Field>[fix].[sow/fix42/orders]./1 AS /Account</Field>
    <Field>SUM([fix].[sow/fix42/orders]./14) AS /CumQty</Field>
    <Field>SUM([fix].[sow/fix42/orders]./14 * [fix].[sow/fix42/orders]./6)
           / SUM([fix].[sow/fix42/orders]./14) AS /AvgPx</Field>
</Projection>
<Grouping>
    <Field>[fix].[sow/fix42/orders]./1</Field>
</Grouping>
```

A bare `<MessageType>json</MessageType>` with an unqualified
`<UnderlyingTopic>` is rejected by `ampServer --verify-config` ("of type
'json' ... has not been defined"). The `<Filter>` may stay unqualified:
`/9000 IS NULL` works as written. Two smaller points from the same runs: a
view takes no `<FileName>` and is not journalled, because AMPS rebuilds it
from the underlying topic at startup; and a fix-typed view over a fix-typed
view does **integer** division when the serialised numerator has no decimal
point (615300 / 1500 came out as 410), so keep a division in the first view
or make the intermediate json.

## What the test pins

The scripted [`MockFixFlow`](../../fix42-publisher/src/main/java/com/demo/amps/fix42/mock/MockFixFlow.java)
produces, live:

| view | row | Orders | OrderQty | LeavesQty | CumQty | AvgPx |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| parent | AAPL / ACC-INSTL-01 / buy | 1 | 12000 | 0 | 12000 | 185.6333 |
| parent | MSFT / ACC-INSTL-01 / sell | 1 | 5000 | 0 | 1500 | 410.2 |
| parent | GOOG / ACC-HEDGE-07 / buy | 1 | 800 | 800 | 0 | null |
| parent | NVDA / ACC-HEDGE-07 / buy | 1 | 4000 | 0 | 1000 | 121.75 |
| parent | TSLA / ACC-INSTL-02 / buy | 1 | 20000 | 0 | 16000 | 242.0931 |
| parent | AMZN / ACC-INSTL-01 / buy | 1 | 6000 | 5000 | 1000 | 210.05 |
| parent | META / ACC-HEDGE-07 / sell | 1 | 3000 | 0 | 3000 | 511.98 |
| child | TSLA / ACC-INSTL-02 / buy | 2 | 20000 | 0 | 16000 | 242.0931 |
| children_by_parent | PARENT-TSLA-1 | 2 | 20000 | 0 | 16000 | 242.0931 |

The last two rows agreeing with the parent's own record is the consistency
check, and it required one change to the mock: the TSLA parent used to report
two partial fills (9000 shares) against children that filled 16000. It now
reports one partial per child fill (5000, 4000, 7000), so its 38/14/151 equal
the roll-up exactly and its AvgPx agrees to the four places the venue rounds
tag 6 to. Exact equality on AvgPx is not on offer in general: the parent's
tag 6 is a running average rounded at every fill, while the roll-up is one
division over the slices' rounded averages.

Side is projected as FIX spells it (`1` buy, `2` sell); the table above
translates for readability only.

## Reconciling the two levels: `view/fix42/recon/parent_vs_child`

`children_by_parent` gives a reader the numbers to compare against a parent's
record; it does not do the comparison. The fourth view does, one level up:
it joins the parent exposure view to the child exposure view on account,
symbol and side, and projects both sides and their difference.

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
        ...
        <Field>[view/fix42/exposure/parent]./CumQty AS /ParentCumQty</Field>
        <Field>[view/fix42/exposure/child]./CumQty AS /ChildCumQty</Field>
        <Field>[view/fix42/exposure/parent]./CumQty - [view/fix42/exposure/child]./CumQty AS /CumQtyDelta</Field>
        ...
    </Projection>
    <Grouping>
        <Field>[view/fix42/exposure/parent]./Account</Field>
        <Field>[view/fix42/exposure/parent]./Symbol</Field>
        <Field>[view/fix42/exposure/parent]./Side</Field>
    </Grouping>
</View>
```

Three things about it were established on 5.3.5.135 the same way as the
findings above, by running it rather than reading about it.

**It must be declared after the views it joins.** A join view's underlying
topics have to be defined earlier in the file, so it closes the `<SOW>`
block. Both sides are json, so the references need no `[json].` type
qualifier, unlike the cross-type views above.

**Finding 4: the join is a LEFT OUTER join from the first topic named.** Every
parent group has a row. The six parents with no slices (all but TSLA) carry
JSON `null` in every child column, and a subtraction over a null is null, so
their deltas are null too. TSLA carries both levels and reconciles with
`CumQtyDelta` and `LeavesQtyDelta` of `0.0`. That is the right behaviour for
a reconciliation, because a parent that has lost all its slices should stay
visible, but it does mean the view cannot by itself say "here are the
breaks".

**Finding 5: a join view takes no `<Filter>`**, so the break selection belongs
to the reader. A `sow` query on the view with

```
/ChildCumQty IS NOT NULL AND /CumQtyDelta != 0
```

returns the rows whose child level exists and disagrees. `IS NOT NULL` is
what keeps the unsliced parents out: a null delta is "nothing to reconcile",
not a break. On the clean scripted flow the query returns no rows.

**What the test pins.** `ReconViewIT` reads the view twice, live and without a
restart, on the same container. First the clean flow, where the query above
is empty and the TSLA row reads:

| | Orders | CumQty | LeavesQty |
| --- | ---: | ---: | ---: |
| parent | 1 | 16000 | 0 |
| child | 2 | 16000 | 0 |
| delta | | 0 | 0 |

Then it publishes, through the publisher's own fix connection, one report the
mock never sends: a further partial fill on the cancelled slice B, abridged
to the tags that matter here
(`35=8|11=CHILD-TSLA-B-2|37=ORD-CHILD-TSLA-B|17=EXEC-CHILD-TSLA-B-99|39=1|150=1|38=8000|14=5000|151=3000|6=242.1|32=1000|31=242.1`),
with no matching partial on the parent. The `exec-partial-fill` route merges
it onto slice B's blotter record, the child view's TSLA row moves to 17000,
and the same query then returns exactly one row:

| | Orders | CumQty | LeavesQty |
| --- | ---: | ---: | ---: |
| parent | 1 | 16000 | 0 |
| child | 2 | 17000 | 3000 |
| delta | | -1000 | -3000 |

The other six rows are unchanged. A break is a row's columns moving, never a
row appearing or disappearing.

**What the key does and does not cover.** Account × symbol × side is the
right reconciliation key only when every parent in a group is sliced. A group
that mixes a sliced parent with an unsliced one under the same account,
symbol and side breaks by construction, because the unsliced parent's fills
have no child counterpart. The finer check, each parent's own record against
`children_by_parent`, would need the parent's *root* ClOrdID retained on the
blotter: `children_by_parent` groups on tag 9000, which names the ClOrdID the
slices were opened under, while the parent record's tag 11 rotates on every
amend and tag 9014 follows the working id. Neither is stable across the
chain, so a join between them has nothing to join on. That is a small blotter
change (one more field stamped from the `35=D` and never rewritten) and is
not built.
