# Local verification plan

A hand-off document: everything in this repository was originally built and
unit-tested in a cloud sandbox that could not pull the AMPS image or reach
crankuptheamps.com, so no demo had ever run against a live AMPS instance. This
plan verifies the whole project on a local machine with podman, one feature at a
time, in an order chosen so failures are informative. **It has now been executed
once — see the result banner below — and is still the procedure to repeat on a
new AMPS version or a new machine.**

Working through this with a Claude session? Give it this file and say "follow
the verification plan". Check items off as they pass; on any failure, capture
the exact error output — the [failure playbook](#failure-playbook) at the bottom
pre-diagnoses the likely ones.

Branch: `claude/amps-60-east-demo-lmwsjc` · PR: crazymatthsu/amps-demo#2.
Config fixes discovered during verification belong on this branch so the PR
stays the single record of the working state.

> ## Executed 2026-08-17 — all 16 demos pass
>
> **Environment:** AMPS **5.3.5.135** (Release-Linux tarball, built via
> `server/Containerfile`), macOS on Apple Silicon (arm64) running the amd64
> image under emulation, podman 5.8.2, JDK 21 toolchain.
>
> Six defects were found and fixed; every one of them blocked or corrupted a
> step of this plan:
>
> | # | Defect | Fix |
> | --- | --- | --- |
> | 1 | `docker.io/amps/ce:latest` — the documented fallback image and `amps.sh`'s default — is **not 60East AMPS**. It is an unrelated Apache/MySQL/PHP product sharing the acronym; no `ampServer` exists in it at any path. | Default removed; `amps.sh` now requires `AMPS_IMAGE` and rejects that name explicitly. Docs corrected. |
> | 2 | `amps.sh validate` passed `--test-config`, which this AMPS does not accept — and ampServer **ignores an unknown flag and starts the instance**, so "validate" silently launched servers and hung. | Reads the advertised flag from `ampServer --help` (`--verify-config` here) instead of guessing. |
> | 3 | The regex SOW topic was written as `<Name>^desk\.…$</Name>`. AMPS takes that literally, creating one SOW nothing routes into — every `desk.*` query returned 0. | Uses `<Name>desk</Name>` + `<Pattern>^desk\.…$</Pattern>`, the actual mechanism. |
> | 4 | `<Module>amps-action-do-delete-old-journal-files</Module>` does not exist, so `amps-config-bounded-retention.xml` was rejected outright. | Corrected to `amps-action-do-remove-journal`. |
> | 5 | `amps.sh wait` returned as soon as the TCP port accepted, but AMPS binds transports *before* it finishes initialising — the recovery demo failed with "Socket error while sending message". | Waits for the server's startup-complete line, on a wall-clock deadline. |
> | 6 | `MinJournalSize` of `4MB` is below AMPS's 10MB floor and was silently reset, so the configs and docs described a size the server never used. | Set to `10MB` in both configs; docs and demo text updated. |
>
> Two elements this plan flagged as most likely to be rejected were in fact
> **accepted as written**: the fix/nvfix key syntax (`/9001`, `/37`, `/EventId`,
> `/OrderID`) and, once moved into `<Pattern>`, the desk regex.
>
> Benign on this setup, worth knowing: AMPS warns that dotted topic names
> (`bench.full`, `fix.events`) "have regular expression characters", and under
> emulation logs `transaction log aio init failed … Function not implemented`
> before falling back. Neither affects any result below.
>
> **Note added after this run:** the config layout below has since been
> reorganised — `server/config/amps-config*.xml` became
> `server/config/flows/<flow>/amps-config.xml`, and `amps.sh validate
> amps-config-bounded-retention.xml` became `amps.sh validate
> bounded-retention`. This record describes what actually ran and is left as
> written; see [server-env-layering.md](docs/src/server-env-layering.md) for
> the current layout and command forms.

---

## Step 0 — machine check

- [x] `podman --version` works (or docker; the scripts fall back automatically).
      *Verified 2026-08-17: podman 5.8.2, macOS arm64, no docker.*
- [x] Ports 9007, 9008 and 8085 are free.
- [x] **Apple Silicon only:** the AMPS server binary is Linux x86_64, so both
      the image build and every `podman run` need `--platform linux/amd64`, and
      the podman machine needs Rosetta/qemu emulation. Intel/Linux: skip.
      *Verified: emulation works on this machine (libkrun); `amps.sh` now passes
      `--platform` itself, so only the `podman build` below needs the flag by
      hand. Override with `AMPS_PLATFORM`.*

## Step 1 — build the AMPS image

**There is no alternative to this step.** The server is only distributed as a
release tarball, behind the email sign-up at
<https://www.crankuptheamps.com/evaluate/>. Get a current 5.3.x server, matching
the 5.3.5.3 Java client this repo builds against:

- [x] Download the Linux release tarball from
      <https://www.crankuptheamps.com/evaluate/>.
- [x] Drop it in `server/vendor/` (git-ignored — note that 5.3.5.135 arrives as
      a plain `.tar`, not `.tar.gz`; both are ignored and both build).
- [x] Build, using the exact filename you downloaded:

```bash
podman build --platform linux/amd64 -f server/Containerfile -t amps-demo:5.3 \
    --build-arg AMPS_TARBALL=<exact-filename>.tar.gz server
export AMPS_IMAGE=amps-demo:5.3
```

> **The public-image fallback this plan used to describe does not exist.**
> Verified 2026-08-17: `docker.io/amps/ce:latest` is not 60East AMPS. It calls
> itself "AMPS Community Edition" on Docker Hub, but it is an unrelated
> Apache/MySQL/PHP application — `find / -name ampServer` inside it returns
> nothing, `/opt` is empty, and its entrypoint starts supervisord running
> apache2, mysql and php. `amps.sh` now rejects the name outright, and no
> longer defaults `AMPS_IMAGE` to anything.

If the container exits immediately on start, the binary is not at the default
path in that image: `./server/scripts/amps.sh probe`, then
`AMPS_BIN=<path-it-reports> ./server/scripts/amps.sh start`.

## Step 2 — validate the configs (before starting anything)

This answers, in one command each, every element the repo flags as
version-sensitive:

- [x] `./server/scripts/amps.sh validate`
- [x] `./server/scripts/amps.sh validate bounded-retention`
- [x] `./server/scripts/amps.sh validate market-data`

(Commands as run at the time — `validate amps-config-bounded-retention.xml`
and `validate amps-config-market-data.xml`; the config layout has since moved
to `server/config/flows/<flow>/amps-config.xml`, so `validate` now takes the
flow name instead of a filename. `./server/scripts/amps.sh flows` lists them.)

*All three accepted with zero warnings on 5.3.5.135, after the fixes in the
table at the top of this file. Note that `validate` itself was broken — it used
a flag this build does not have, and ampServer responds to an unknown flag by
starting the instance rather than failing, so it hung instead of reporting.*

The three elements most likely to be rejected, and what actually happened:

| element | where | outcome on 5.3.5.135 |
| --- | --- | --- |
| `<Actions>` journal-ageing block | bounded-retention (in market-data it is inside a comment, so it is never parsed) | **rejected** — no such module as `amps-action-do-delete-old-journal-files`; the real one is `amps-action-do-remove-journal`, which accepts `<Age>` |
| regex SOW topic `^desk\.[A-Za-z0-9_-]+$` | flows/default/amps-config.xml | **accepted but inert** in `<Name>` — AMPS treats it as a literal topic name and warns about "regular expression characters". Belongs in `<Pattern>`, with `<Name>` naming the physical topic |
| fix/nvfix key syntax `/9001`, `/37`, `/EventId`, `/OrderID` | flows/default/amps-config.xml | **accepted as written**, and demos 13/14 confirm it works end to end |

## Step 3 — start and smoke-test

- [x] `./server/scripts/amps.sh start` — waits for readiness, prints endpoints.
- [x] `./server/scripts/amps.sh status` reports running.
- [x] `./server/scripts/amps.sh logs | tail -30` — no config errors; the
      declared topics are mentioned.
- [x] Admin UI at <http://localhost:8085/> shows the instance and topics.
      *Responds HTTP 200.*
- [x] `./gradlew build` — 49 unit tests, green (needs no server; if this fails,
      fix it before touching the demos).

## Step 4 — the demos, one by one

Run each with `./gradlew :clients:run --args="<name>"`. The pass column is what
correct output looks like; anything materially different is a finding.

- [x] **1. `pubsub`** — 5 published, 5 received; **latecomer replays 0**
      (undeclared topics have no history). *Exactly as described.*
- [x] **2. `dynamic-topics`** — one regex subscription catches all 5 invented
      topics; the `desk.*` SOW counts are **non-zero**. *Initially 0/0/0 — the
      regex was in `<Name>`, where AMPS reads it literally. With `<Pattern>`,
      3 records per desk. The three desks share one physical `desk.json.sow`;
      they do not get a file each, which is what this plan used to claim.*
- [x] **3. `sow-load`** — 200 orders + 10 instruments published, no errors.
- [x] **4. `sow-query`** — unfiltered count 200; the filtered, ordered, top-N
      query returns rows; `group_begin`/`group_end` markers print. *Filter
      matched 54; projection works.*
- [x] **5. `sow-query-by-key`** — lookup by `/orderId` and by server-assigned
      SOW key both return the record; the follow subscription sees 2 live
      updates.
- [x] **6. `sow-and-subscribe`** — snapshot count > 0, one live insert, then
      **one OOF** when the order is filled out of the filter. *43 / 1 / 1.*
- [x] **7. `delta-publish`** — "quote merged as predicted: true" and
      "reference data still intact: true"; ~10x size reduction reported.
      *1326 B → 132 B, 10.0x.*
- [x] **8. `delta-subscribe`** — snapshot ~1.3 KB delivered once, then 6 deltas
      of ~134 B each. *9.9x on the update stream.*
- [x] **9. `bookmark-replay`** — run it **twice**: the second run's resumable
      count drops to roughly what was published in between. *276 → 5.*
- [x] **10. `expiration`** — record count drops to 0 after the TTL with OOF
      reason `expired`; nothing was deleted by any client. *4 → 0, 4 OOFs.*
- [x] **11. `truncate`** — server-side `recordsDeleted` matches expectations;
      the journal did **not** shrink (a delete is a write). *10 OOF `deleted`.*
- [x] **12. `fix-lifecycle`** — acked qty holds at 500 while the replace to 800
      is pending; the PossDup resend prints IGNORED; final state FILLED,
      CumQty 800, AvgPx 100.04, chain id still A1. *All confirmed.*
- [x] **13. `fix-native`** — raw `35=…|11=…` payloads print; orders SOW holds
      4 orders; `/39 = '2'` filter finds 2 filled; chain-A1 history filter
      finds 7 events. *All four numbers hit; tag-number SOW keys work.*
- [x] **14. `nvfix-native`** — same numbers, named fields (`/OrdStatus = '2'`).
- [x] **15. `recovery`** —
      `--phase snapshot` → `./server/scripts/amps.sh restart` →
      `--phase verify`: SOW counts match, replay from the saved bookmark works.
      Then repeat with `podman kill amps-demo && ./server/scripts/amps.sh start`
      for the unclean-shutdown case (this exercises SOW/journal reconciliation).
      *Both cases: 241 records and 25/25 markers survive, SOW intact. This is
      the demo that exposed the premature-readiness bug in `amps.sh wait`.*
- [x] **16. `journal-lab`** — ~10x payload reduction between the full and delta
      phases; on-disk growth moves in 10 MB steps (preallocation — expected, not
      a bug; zero growth in either phase is a pass). *12.8x payload reduction.
      The on-disk ratio is only reported when both phases actually crossed a
      file boundary — it used to print "0.0x" when one of them grew by nothing,
      which reads as "deltas cost more" and is purely an artefact of measuring
      10 MB-quantised growth.*

Steps 1–10 can be batched as `--args="tour"`, but one-by-one isolates failures.

## Step 5 — record the outcome

- [x] Check off what passed; write down exact output for what did not.
- [x] Commit the fixes with a note of the AMPS version they were verified
      against. *(Committed on branch `fix/amps-verification-5.3.5.135`; not pushed.)*
- [x] Update README "Before you rely on it" if any verify-on-your-build caveat
      is now confirmed and can be stated as fact for this version.

---

## Failure playbook

**`AMPS_IMAGE is not set`.** By design — there is no public image to fall back
on. Build one from a release tarball; see step 1.

**Container exits immediately after `start`.** Binary path. `amps.sh probe`,
then `AMPS_BIN=<reported> ./server/scripts/amps.sh start`.

**`validate` rejects an element.** See the table in step 2.

**`validate` hangs instead of answering.** It ran a flag this ampServer does not
know, and an unrecognised flag makes AMPS *start the instance* rather than fail.
Fixed here by reading the flag out of `ampServer --help`, but if you see it
again: `podman ps` and kill whatever `validate` left running.

**Demos fail with "Socket error while sending message" right after a restart.**
AMPS binds its transports before it has finished initialising, so a port check
alone says "ready" too early. `amps.sh wait` now waits for the startup-complete
line in the log; a client of your own needs to retry the first connection.

**`sow-query` returns 0 after a clean `sow-load`.** The SOW key is not
resolving. Check the startup log for the `orders` topic; the key must be the
lowerCamelCase JSON member (`/orderId`). If the log is clean, capture one
published record from the admin console and compare its member names.

**`dynamic-topics` reports 0 desk records.** The regex must be in `<Pattern>`,
with `<Name>` naming the physical topic. Put a regex in `<Name>` and AMPS
accepts it, warns about "regular expression characters", and then treats it as
a literal name that nothing routes into.

**`bookmark-replay` delivers 0 on the first run.** Its durable bookmark store
lives in `build/client-state/`, outside the server's data directory, so
`amps.sh reset` does not clear it. `rm -rf build/client-state` for a true
cold start.

**`fix-native` / `nvfix-native` publishes fail or the SOW stays empty.** The
field-reference form for fix/nvfix keys (`/9001`, `/37`, `/OrderID`) is
confirmed working on 5.3.5.135. If a different build rejects it, the error line
from `amps.sh logs` names what the parser expected.

**`expiration` expires nothing.** Confirm `validate` accepted `<Expiration>` on
`quote-cache`, and that the TTL actually elapsed (`--ttl 3 --wait 10`).

**A numeric filter returns wrong rows.** The field is rendering as a quoted
string (int64 rule) — see docs/src/protobuf-json-and-amps.md rule 1.

**Demo can't connect.** `./server/scripts/amps.sh status`; the demos read
`AMPS_HOST`/`AMPS_PORT` if the instance is not on localhost:9007.
