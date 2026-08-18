# Local verification plan

A hand-off document: everything in this repository was built and unit-tested in
a cloud sandbox that could not pull the AMPS image or reach crankuptheamps.com,
so **no demo has ever run against a live AMPS instance**. This plan verifies the
whole project on a local machine with podman, one feature at a time, in an order
chosen so failures are informative.

Working through this with a Claude session? Give it this file and say "follow
the verification plan". Check items off as they pass; on any failure, capture
the exact error output — the [failure playbook](#failure-playbook) at the bottom
pre-diagnoses the likely ones.

Branch: `claude/amps-60-east-demo-lmwsjc` · PR: crazymatthsu/amps-demo#2.
Config fixes discovered during verification belong on this branch so the PR
stays the single record of the working state.

---

## Step 0 — machine check

- [ ] `podman --version` works (or docker; the scripts fall back automatically).
- [ ] Ports 9007, 9008 and 8085 are free.
- [ ] **Apple Silicon only:** the AMPS server binary is Linux x86_64. Add
      `--platform linux/amd64` to `podman build`, and confirm the podman
      machine has Rosetta/qemu emulation enabled. Intel/Linux: skip.

## Step 1 — build the AMPS image

**Preferred: a current 5.3.x server**, matching the 5.3.5.3 Java client this
repo builds against:

- [ ] Download the Linux release tarball from <https://www.crankuptheamps.com/>.
- [ ] Drop it in `server/vendor/` (git-ignored).
- [ ] Build, using the exact filename you downloaded:

```bash
podman build -f server/Containerfile -t amps-demo:5.3 \
    --build-arg AMPS_TARBALL=<exact-filename>.tar.gz server
export AMPS_IMAGE=amps-demo:5.3
```

**Fallback: the public image** (`docker.io/amps/ce:latest`, a 2017 build —
expect more config rejections):

```bash
unset AMPS_IMAGE            # amps.sh then uses the default
```

If the container exits immediately on start, the binary is not at the default
path in that image: `./server/scripts/amps.sh probe`, then
`AMPS_BIN=<path-it-reports> ./server/scripts/amps.sh start`.

## Step 2 — validate the configs (before starting anything)

This answers, in one command each, every element the repo flags as
version-sensitive:

- [ ] `./server/scripts/amps.sh validate`
- [ ] `./server/scripts/amps.sh validate amps-config-bounded-retention.xml`
- [ ] `./server/scripts/amps.sh validate amps-config-market-data.xml`

The three elements most likely to be rejected, and their fallbacks (none loses
the rest of the demo):

| element | where | if rejected |
| --- | --- | --- |
| `<Actions>` journal-ageing block | bounded-retention + market-data configs | delete the block; instance starts without it; note the module name your version wants (AMPS User Guide, "Actions") |
| regex SOW topic `^desk\.[A-Za-z0-9_-]+$` | amps-config.xml | comment the topic out; `dynamic-topics` demo's desk section will report zero and say why — everything else passes |
| fix/nvfix key syntax `/9001`, `/37`, `/EventId`, `/OrderID` | amps-config.xml | capture the error; the fix is usually the reference form, not the design |

## Step 3 — start and smoke-test

- [ ] `./server/scripts/amps.sh start` — waits for the port, prints endpoints.
- [ ] `./server/scripts/amps.sh status` reports running.
- [ ] `./server/scripts/amps.sh logs | tail -30` — no config errors; the
      declared topics are mentioned.
- [ ] Admin UI at <http://localhost:8085/> shows the instance and topics.
- [ ] `./gradlew build` — 49 unit tests, green (needs no server; if this fails,
      fix it before touching the demos).

## Step 4 — the demos, one by one

Run each with `./gradlew :clients:run --args="<name>"`. The pass column is what
correct output looks like; anything materially different is a finding.

- [ ] **1. `pubsub`** — 5 published, 5 received; **latecomer replays 0**
      (undeclared topics have no history).
- [ ] **2. `dynamic-topics`** — one regex subscription catches all 5 invented
      topics; the `desk.*` SOW counts are **non-zero** (live proof of the regex
      SOW topic — zero here means that config block was rejected or altered).
- [ ] **3. `sow-load`** — 200 orders + 10 instruments published, no errors.
- [ ] **4. `sow-query`** — unfiltered count 200; the filtered, ordered, top-N
      query returns rows; `group_begin`/`group_end` markers print.
- [ ] **5. `sow-query-by-key`** — lookup by `/orderId` and by server-assigned
      SOW key both return the record; the follow subscription sees 2 live
      updates.
- [ ] **6. `sow-and-subscribe`** — snapshot count > 0, one live insert, then
      **one OOF** when the order is filled out of the filter.
- [ ] **7. `delta-publish`** — "quote merged as predicted: true" and
      "reference data still intact: true"; ~10x size reduction reported.
- [ ] **8. `delta-subscribe`** — snapshot ~1.3 KB delivered once, then 6 deltas
      of ~134 B each.
- [ ] **9. `bookmark-replay`** — run it **twice**: the second run's resumable
      count drops to roughly what was published in between (the bookmark store
      under `build/client-state/` is doing its job).
- [ ] **10. `expiration`** — record count drops to 0 after the TTL with OOF
      reason `expired`; nothing was deleted by any client.
- [ ] **11. `truncate`** — server-side `recordsDeleted` matches expectations;
      the journal did **not** shrink (a delete is a write).
- [ ] **12. `fix-lifecycle`** — acked qty holds at 500 while the replace to 800
      is pending; the PossDup resend prints IGNORED; final state FILLED,
      CumQty 800, AvgPx 100.04, chain id still A1.
- [ ] **13. `fix-native`** — raw `35=…|11=…` payloads print; orders SOW holds
      4 orders; `/39 = '2'` filter finds 2 filled; chain-A1 history filter
      finds 7 events.
- [ ] **14. `nvfix-native`** — same numbers, named fields (`/OrdStatus = '2'`).
- [ ] **15. `recovery`** —
      `--phase snapshot` → `./server/scripts/amps.sh restart` →
      `--phase verify`: SOW counts match, replay from the saved bookmark works.
      Then repeat with `podman kill amps-demo && ./server/scripts/amps.sh start`
      for the unclean-shutdown case (this exercises SOW/journal reconciliation).
- [ ] **16. `journal-lab`** — ~10x payload reduction between the full and delta
      phases; on-disk growth moves in 4 MB steps (preallocation — expected, not
      a bug; zero growth in the delta phase is a pass).

Steps 1–10 can be batched as `--args="tour"`, but one-by-one isolates failures.

## Step 5 — record the outcome

- [ ] Check off what passed; write down exact output for what did not.
- [ ] Commit config fixes (if any) to `claude/amps-60-east-demo-lmwsjc` with a
      note of the AMPS version they were verified against — PR #2 updates
      automatically on push.
- [ ] Update README "Before you rely on it" if any verify-on-your-build caveat
      is now confirmed and can be stated as fact for this version.

---

## Failure playbook

**Container exits immediately after `start`.** Binary path. `amps.sh probe`,
then `AMPS_BIN=<reported> ./server/scripts/amps.sh start`.

**`validate` rejects an element.** See the table in step 2. Nothing there is
load-bearing for the rest of the suite.

**`sow-query` returns 0 after a clean `sow-load`.** The SOW key is not
resolving. Check the startup log for the `orders` topic; the key must be the
lowerCamelCase JSON member (`/orderId`). If the log is clean, capture one
published record from the admin console and compare its member names.

**`fix-native` / `nvfix-native` publishes fail or the SOW stays empty.** The
field-reference form for fix/nvfix keys (`/9001`, `/37`, `/OrderID`) is the one
piece of native-FIX config written without a server to check against. The error
line from `amps.sh logs` names what the parser expected.

**`expiration` expires nothing.** Confirm `validate` accepted `<Expiration>` on
`quote-cache`, and that the TTL actually elapsed (`--ttl 3 --wait 10`).

**A numeric filter returns wrong rows.** The field is rendering as a quoted
string (int64 rule) — see docs/src/protobuf-json-and-amps.md rule 1.

**Demo can't connect.** `./server/scripts/amps.sh status`; the demos read
`AMPS_HOST`/`AMPS_PORT` if the instance is not on localhost:9007.
