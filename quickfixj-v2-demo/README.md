# quickfixj-v2-demo

A **QuickFIX/J 2.x drop-copy FIX engine on Spring Boot**, with two things
bolted on that a plain engine does not have:

1. **Spring Integration carries every received message** through a list of
   configurable enrichment rules and on to AMPS topics and/or other FIX
   sessions -- all of it YAML, none of it code.
2. **Its sequence numbers are replicated to AMPS.** QuickFIX/J still writes
   its file store on every message; a write-behind thread then publishes the
   next sender/target numbers as a checkpoint record. An instance starting on
   an empty disk -- the DR box -- reads that record back, seeds its file
   store from it, and logs on where the last instance left off, with no
   resend request and no manual resequence.

```
quickfixj-v2-demo/
├── config/
│   ├── dictionary/FIX42.xml     the standard FIX 4.2 dictionary + the four drop-copy tags
│   ├── venue/     quickfixj.cfg + venue.yml       acceptor: plays the venue, invents execution reports
│   └── dropcopy/  quickfixj.cfg + dropcopy.yml    initiator: receives them -> rules -> AMPS
├── src/main/java/com/demo/amps/qfj2/
│   ├── seqno/      the AMPS-replicated MessageStore: store, write-behind publisher, recovery, admin
│   ├── engine/     QuickFIX/J as a Spring lifecycle bean, and the Application that feeds the flow
│   ├── flow/       Spring Integration: the rules, the destinations, the IntegrationFlow
│   ├── mock/       the venue's execution-report feed
│   └── admin/      the seqno-admin profile (manual resequencing)
├── compose.yml, Containerfile, scripts/dropcopy-compose.sh   the three-container stack
├── src/test/                 unit tests, including two real engines over loopback
└── src/integrationTest/      the failover, against a throwaway AMPS container
```

Built with Gradle against QuickFIX/J **2.3.3** (the last 2.x release),
Spring Boot 3.5 / Spring Integration 6.5, on JDK 21.

## Running the demo, step by step

Two ways to run it: on your laptop with Gradle (each engine in a terminal),
or as three containers with the compose script. Both need an AMPS image and
a JDK; Gradle fetches its own JDK 21 toolchain.

### 0. Prerequisites, once

1. **An AMPS image.** There is no public one; build it from the release
   tarball as [server/README.md](../server/README.md) describes, then export
   its tag **exactly as `podman images` lists it**. Every command below that
   starts AMPS, and the integration test, needs this variable.

   ```bash
   podman images | grep amps-demo            # e.g. localhost/amps-demo   5.3.5.135
   export AMPS_IMAGE=localhost/amps-demo:5.3.5.135
   ```

   A tag podman cannot find locally is looked up on Docker Hub instead, and
   the failure reads `Trying to pull docker.io/library/amps-demo:... access
   to the resource is denied`. That means the variable, not the image: check
   the tag with `podman image exists "$AMPS_IMAGE"`. With the variable unset
   the integration test skips rather than fails.

2. **podman** (or docker) with a compose provider, for the container path
   and the integration test. `podman compose version` should print something.

3. **Build the module and run its unit tests.** No AMPS needed for this.

   ```bash
   ./gradlew :quickfixj-v2-demo:build
   ```

### Option A: on your laptop, with Gradle

Everything here runs from the repository root. The engines write their
QuickFIX/J store and logs under `quickfixj-v2-demo/data/` and
`quickfixj-v2-demo/log/` (both git-ignored).

1. **Start AMPS on this module's flow.** The flow declares the checkpoint
   topic and the two drop-copy topics.

   ```bash
   AMPS_FLOW=quickfixj-dropcopy ./server/scripts/amps.sh start
   ```

   `./server/scripts/amps.sh status` should show it up on 9007/9008/8085.

2. **Start the venue** (terminal 1): an acceptor on port 9876 that invents
   one execution report every two seconds once someone logs on.

   ```bash
   ./gradlew :quickfixj-v2-demo:bootRun -Prole=venue
   ```

   Wait for `FIX acceptor started`. Its own sequence numbers are checkpointed
   to AMPS too, under `FIX.4.2:VENUE->DROPCOPY`.

3. **Start the drop-copy consumer** (terminal 2): an initiator that logs on
   to the venue and pushes every report through the rules onto AMPS.

   ```bash
   ./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy
   ```

   Within a few seconds you should see, in this order:

   ```
   seqno recovery for FIX.4.2:DROPCOPY->VENUE: file held 1/1; no checkpoint in AMPS: the file's 1/1 stands ...
   logged on: FIX.4.2:DROPCOPY->VENUE
   publish sow/dropcopy/fix42/execs [execs-blotter] 8=FIX.4.2|9=294|35=8|34=2|49=VENUE|...|5001=VENUE-DROPCOPY|5002=VENUE|5003=FIX.4.2:DROPCOPY->VENUE|5004=20260918-03:14:50.117|10=166|
   ```

   The four `500x` tags are the enrichment rules from
   [config/dropcopy/dropcopy.yml](config/dropcopy/dropcopy.yml).

4. **Watch the blotter fill** (terminal 3), from the SOW keyed on ExecID:

   ```bash
   ./gradlew :amps-cli:run --args="--url tcp://127.0.0.1:9007/amps/fix --topic sow/dropcopy/fix42/execs --mode snapshot"
   ```

   or open the admin UI at <http://127.0.0.1:8085/> and query
   `sow/dropcopy/fix42/execs`. The checkpoints themselves are on
   `sow/quickfixj/seqno` (json).

5. **Compare the file store with the checkpoint** while the consumer runs
   (`show` is read-only, so it is safe with the engine up):

   ```bash
   ./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy --args="--spring.profiles.active=seqno-admin --seqno.action=show"
   ```

   ```
   FIX.4.2:DROPCOPY->VENUE            file 3/51         AMPS 3/51         in sync   rev 53, dropcopy-primary, 2026-09-18T03:24:25Z
   ```

6. **The failover.** Stop the consumer (Ctrl-C in terminal 2) and throw its
   disk away; the venue stays up, waiting.

   ```bash
   rm -rf quickfixj-v2-demo/data/store
   ```

   Start it again exactly as in step 3. This time the recovery line reads:

   ```
   seqno recovery for FIX.4.2:DROPCOPY->VENUE: file held 1/1; policy amps-wins: AMPS checkpoint 4/46 (rev 49, dropcopy-primary, ...) replaces the file's 1/1; starting with next sender/target 4/46
   logged on: FIX.4.2:DROPCOPY->VENUE
   ```

   and the next publish carries the next MsgSeqNum the venue was going to
   send anyway. No `ResendRequest` (35=2) appears in either log, and the
   venue never saw a `MsgSeqNum too low`. Run step 5 again: in sync.

7. **A manual resequence**, for the case replication could not cover (the
   primary died with its last increments unreplicated, and the counterparty
   logged you out with `MsgSeqNum too low`). Stop the consumer first; the
   file actions need the engine down.

   ```bash
   # both sides of the session must agree: our next sender is their next target
   ./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy --args="--spring.profiles.active=seqno-admin --seqno.action=set-amps --seqno.sender=48"
   ./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy --args="--spring.profiles.active=seqno-admin --seqno.action=show"
   ```

   Start the consumer again (step 3): under `amps-wins` the checkpoint you
   wrote is what it starts with. `set-file` edits the file instead,
   `file-to-amps` / `amps-to-file` copy one side onto the other, and
   `--qfj.seqno.enabled=false` on the command line makes the tool work on
   the file alone when AMPS is not reachable from where you are.

8. **Clean up.** Ctrl-C both engines, then:

   ```bash
   rm -rf quickfixj-v2-demo/data quickfixj-v2-demo/log
   ./server/scripts/amps.sh stop          # or `reset` to delete the SOW and journal too
   ```

### Option B: in containers, with podman compose

[scripts/dropcopy-compose.sh](scripts/dropcopy-compose.sh) drives
[compose.yml](compose.yml): AMPS, the venue and the consumer, each with its
config, data and log folders bind-mounted from `quickfixj-v2-demo/deploy/`
(git-ignored). The engines find AMPS and each other by service name.

1. **Build the engine image** (the boot jar on a JRE, nothing else):

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh build
   ```

2. **Start the stack.** It creates the `deploy/` folders, starts AMPS, waits
   for its own readiness line, then starts both engines.

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh start        # AMPS_IMAGE exported as above
   ```

   If the repo's own AMPS is already on 9007, move the stack's host ports:

   ```bash
   QFJ_AMPS_PORT=29107 QFJ_AMPS_WS_PORT=29108 QFJ_AMPS_ADMIN_PORT=28185 QFJ_VENUE_PORT=29876 \
     ./quickfixj-v2-demo/scripts/dropcopy-compose.sh start        # AMPS_IMAGE exported as above
   ```

   (Export the same four variables for every later command.)

3. **Watch the consumer**; the same lines as Option A step 3 appear:

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh logs dropcopy -f
   ```

4. **Compare file and checkpoint**, in a one-off container on the same mounts:

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh seqno dropcopy show
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh seqno venue show
   ```

5. **The failover, scripted.** It removes the consumer container, gives the
   replacement a fresh, empty store directory (`data/store-dr-<stamp>`,
   leaving the primary's `data/store` in place as the lost disk), starts it
   with `QFJ_SEQNO_SOURCE=dropcopy-dr`, and prints the recovery line:

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh failover
   ```

   ```
   1. stopping the primary drop-copy engine (qfj2-dropcopy)
   2. its disk is lost: the replacement gets an empty store, data/store-dr-20260917-233241
      (the primary's data/store, 5 files, is left in place as evidence)
   3. starting the DR instance (source tag: dropcopy-dr)
   ... seqno recovery for FIX.4.2:DROPCOPY->VENUE: file held 1/1; policy amps-wins: AMPS checkpoint 4/46 (rev 49, dropcopy-primary, ...) replaces the file's 1/1; starting with next sender/target 4/46
   ... logged on: FIX.4.2:DROPCOPY->VENUE
   ```

   Step 4 now shows the replacement's own store, in sync, with checkpoints
   stamped `dropcopy-dr`. The chosen store directory is remembered in
   `deploy/dropcopy/.store-dir`, so a later `stop`/`start` resumes on it.

6. **A manual resequence.** Stop the engines for a file action; `set-amps`
   works with them running but takes effect on the next start:

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh stop
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh seqno dropcopy set-file --seqno.sender=48 --seqno.target=131
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh seqno dropcopy show
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh start
   ```

7. **An AMPS outage, if you want to see the delivery guarantee.** Stop AMPS
   under the running engines, then bring it back:

   ```bash
   podman stop qfj2-amps; sleep 25; podman start qfj2-amps
   ```

   While it is down the consumer's log shows one `delivery ... failed ...
   disconnecting` per reconnect interval, not one per report. When AMPS is
   back, the log shows `connected to AMPS ... (attempt 2)` and then the
   venue's whole backlog published with `43=Y`, before live reports resume.
   Step 4 shows both sides in sync with the inbound number well past where
   it was.

8. **Stop, or wipe.** `stop` halts the engines before AMPS, so their final
   checkpoints replicate; `down` also removes the containers; `reset` deletes
   `deploy/` (every store, log, and the AMPS instance's own state).

   ```bash
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh stop
   ./quickfixj-v2-demo/scripts/dropcopy-compose.sh reset
   ```

### The tests, which run the same story unattended

```bash
./gradlew :quickfixj-v2-demo:test                                  # 67 tests, no AMPS
./gradlew :quickfixj-v2-demo:integrationTest        # the failover, against a container; needs AMPS_IMAGE
```

The integration test starts a throwaway AMPS container, runs a venue and a
consumer over loopback, stops the consumer, deletes its directory, starts a
replacement on nothing, and asserts it recovers the primary's exact numbers
and logs on with zero resend requests; a second test writes a resequence to
AMPS for both sides and asserts both engines start with it.

## The data flow

```
FIX engine ──fromApp──▶ fixInboundChannel ──▶ rules (in order) ──▶ destinations (every one that matches)
```

[`config/dropcopy/dropcopy.yml`](config/dropcopy/dropcopy.yml) is the flow:

```yaml
qfj:
  flow:
    channel: direct
    rules:
      - type: set-tag         # tag the copy with where it came from
        tag: 5001
        value: VENUE-DROPCOPY
      - type: copy-tag        # keep the venue's SenderCompID as a body field
        from: 49
        to: 5002
      - type: source-session  # the session it arrived on
        tag: 5003
      - type: received-time   # when the engine saw it
        tag: 5004
    destinations:
      - name: execs-blotter
        type: amps
        topic: sow/dropcopy/fix42/execs
        msg-types: ["8"]
      - name: audit
        type: amps
        topic: dropcopy/fix42/audit
      # - name: downstream
      #   type: fix
      #   session: "FIX.4.2:DROPCOPY->DOWNSTREAM"
      #   msg-types: ["8"]
```

| rule type | parameters | does |
| --- | --- | --- |
| `set-tag` | `tag`, `value` | writes a constant |
| `copy-tag` | `from`, `to` | copies a tag (body first, then header); a missing source is skipped |
| `remove-tag` | `tag` | removes a body tag |
| `source-session` | `tag` | writes the session id, e.g. `FIX.4.2:DROPCOPY->VENUE` |
| `received-time` | `tag` | writes a FIX UTC timestamp with milliseconds |

Any rule takes an optional `msg-types` list. A rule that needs code is a
Spring bean implementing `EnrichmentRule`; it runs after the configured ones.

| destination type | parameters | does |
| --- | --- | --- |
| `amps` | `topic` | a full `publish` of the raw FIX message (header, body, trailer, SOH-separated) on the `/amps/fix` connection, followed by `publishFlush`; the connection is reopened after any failure |
| `fix` | `session` | clones the message, strips the header fields the target session sets itself, and `Session.sendToTarget`s it on that session -- which must be one this engine has in its `quickfixj.cfg`, or the boot fails |

Every destination whose `msg-types` matches gets the message; a `Destination`
bean is added after the configured ones. Both lists are validated at startup
(unknown rule type, missing parameter, duplicate name, a fix destination
naming a session the engine does not have) and refuse to boot on a typo.

### Delivery semantics, and why the channel is `direct`

On the direct channel the rules and destinations run **on the QuickFIX/J
session thread, inside `fromApp`**. An AMPS destination returns only after
`publishFlush`, so by the time `fromApp` returns, AMPS has the message. If a
destination throws instead -- AMPS down, the forwarding session logged off
-- the exception leaves `fromApp`, and QuickFIX/J (with
`RejectMessageOnUnhandledException=N`, the default) **does not increment the
inbound sequence number**. The counterparty's next message is then a gap, the
engine sends a resend request, and the message arrives again with `43=Y`.
Nothing is lost; it is delayed.

By default the engine also **drops the session** on a failed delivery
(`qfj.flow.disconnect-on-delivery-failure`). Without that, a venue that
keeps sending while AMPS is down draws one resend request per report --
every one of them a gap -- and the engine's own sequence number climbs with
each, beyond what the unreachable checkpoint holds (a stack taken down
AMPS-first showed exactly this: sender 5 in AMPS, 45 in the file).
Disconnecting turns it into one reconnect per `ReconnectInterval` and one
resend request for the whole gap once delivery works again.
`DropCopyPipelineTest` runs both variants between two real engines.

The publish-side AMPS connection is reopened after any failed publish (a
plain client never reconnects after an AMPS restart), with a short budget
(`qfj.amps.reconnect-wait-ms`) because that reopen runs on the session
thread. So an AMPS restart costs: deliveries fail, the session drops and
reconnects every `ReconnectInterval`, and the first delivery after AMPS is
back goes through on a fresh connection, followed by the venue's resend of
everything in between.

That makes delivery at-least-once, and the topics are built for it: the
blotter is a SOW keyed on ExecID (tag 17), so a redelivered report overwrites
its own record; the audit topic is journal-only and simply holds both. A
`fix` destination will forward twice, so list it first if you have one.

`channel: executor` hands off to one ordered background thread instead --
`fromApp` returns at once, throughput is whatever AMPS takes -- at the price
that a message in that queue is lost if the process dies.

## The sequence-number store

QuickFIX/J keeps two numbers per session, the **next** MsgSeqNum it will send
and the **next** it expects to receive, in its file store
(`FileStorePath`: `<prefix>.senderseqnums` and `<prefix>.targetseqnums` in
2.x), and rewrites them on every message in either direction, heartbeats
included. Lose that file and the session cannot resume: the counterparty
expects number 131 and gets a logon with 1.

`AmpsReplicatedFileStoreFactory` is the `MessageStoreFactory` this module
hands QuickFIX/J. What it makes:

```
QuickFIX/J ──▶ AmpsReplicatedFileStore ──▶ FileStore (the .seqnums files, written synchronously)
                       │
                       └──offer──▶ WriteBehindPublisher ──publish+flush──▶ sow/quickfixj/seqno
```

- **The file is written first, always.** Replication is never on the FIX
  session's own durability path, and the file is never behind AMPS.
- **The write-behind thread publishes the newest, and only the newest.**
  It keeps one pending checkpoint per session; a later change replaces the
  earlier one before it is sent. Under load that is a stream of checkpoints
  each carrying the numbers current when it was taken; idle, one publish
  per change. A failed publish is retried after a backoff unless something
  newer has arrived; `close()` drains what is pending, and a checkpoint
  offered after that (a stopping engine recording its logout) is published
  inline. `WriteBehindPublisherTest` covers each of those.
- **The checkpoint is a JSON record**, one per session on a SOW keyed by
  session id, journalled too so the history is a queryable audit trail:

  ```json
  {"sessionId":"FIX.4.2:DROPCOPY->VENUE","nextSenderMsgSeqNum":47,"nextTargetMsgSeqNum":131,
   "creationTime":"2026-09-17T08:00:00Z","updatedAt":"2026-09-17T08:14:03.117Z",
   "source":"dropcopy-primary","revision":176}
  ```

  `source` is the instance that wrote it, `revision` counts checkpoints per
  session across instances (a DR box carries on from the number it found).
- **It holds its own `/amps/json` connection.** The topic is json-typed and
  a connection serves one message type, so the engine has two: this one,
  and the `/amps/fix` one the destinations publish on.

### Failover: what a starting engine does

At store creation -- before QuickFIX/J reads a single number -- the factory
opens the file, reads the session's checkpoint from AMPS, decides, writes the
decision to the file if it differs, and publishes the result as a fresh
checkpoint. The decision is `qfj.seqno.recovery`:

| policy | when file and AMPS disagree | for |
| --- | --- | --- |
| `amps-wins` *(default)* | AMPS's numbers are written to the file | the DR box: empty or stale disk |
| `file-wins` | the file stands and is published to AMPS | a primary that knows its disk is right; observe-only replication |
| `highest` | the higher of each number | a primary restarting after a crash that may have lost the last in-flight replication |

No checkpoint in AMPS means the file stands whatever the policy (the first
ever start). If AMPS **cannot be read** and `qfj.seqno.require-amps` is true
(the default, unless the policy is `file-wins`), the engine refuses to
start: starting on a stale file is exactly the failure this store exists to
prevent, and the error names the two knobs that override it.

The decision is a pure function, `SeqnoRecovery.decide`, so every cell of
that matrix is a unit test (`SeqnoRecoveryTest`), and the line it logs says
what happened and what to expect. The one to read carefully:

> policy amps-wins: AMPS checkpoint 45/130 ... replaces the file's 47/131;
> **NOTE the file is AHEAD of AMPS**, so the previous instance's last
> increments were not replicated before it stopped -- expect a resend request
> or a 'MsgSeqNum too low' logout from the counterparty ...

That is the window replication cannot close: the primary died between
writing the file and the write-behind publish completing. If only the
inbound number lagged, the counterparty sees our resend request and resends
-- harmless, the blotter overwrites. If the outbound number lagged, the
counterparty sees a MsgSeqNum lower than it expects and logs us out; that is
what the manual resequence below is for, and what `highest` avoids on a box
that still has its file.

`SeqnoFailoverIT` runs the real thing against a throwaway AMPS container:
five reports through a primary, a clean stop (the logout's increment
replicated too, so file and AMPS agree), the primary's directory deleted, a
DR instance started on nothing -- it recovers `applied=AMPS` with the
primary's exact numbers, logs on with **zero** resend requests in either
direction, both sides agree, two more reports flow, and its checkpoints
carry on the revision sequence under `source=consumer-dr`.

### What is not replicated

Message bodies. QuickFIX/J stores every sent message so it can answer a
resend request; this store leaves those in the file only. A DR instance
asked to resend what the primary sent answers with a sequence-reset gap
fill, which is the right answer for drop copy -- the copies are already in
AMPS.

## Manual resequencing

The same jar, the `seqno-admin` profile, the engine's own config folder:

```bash
# bootRun form (-Prole picks the config; --args is the tool's own command line)
./gradlew :quickfixj-v2-demo:bootRun -Prole=dropcopy \
    --args="--spring.profiles.active=seqno-admin --seqno.action=show"
# file only, when AMPS is not reachable from here:
#   --args="--spring.profiles.active=seqno-admin --seqno.action=show --qfj.seqno.enabled=false"

# compose form: a one-off container on the same mounts
./quickfixj-v2-demo/scripts/dropcopy-compose.sh seqno dropcopy show
./quickfixj-v2-demo/scripts/dropcopy-compose.sh seqno dropcopy set-amps --seqno.sender=48 --seqno.target=131
```

| `--seqno.action=` | does |
| --- | --- |
| `show` | both sides per session: the file's numbers and path, AMPS's numbers with revision, source and time, and whether they agree |
| `set-file` | rewrites the file, through QuickFIX/J's own `FileStoreFactory` (never by hand) |
| `set-amps` | publishes a checkpoint with the given numbers, `source=admin@<host>` |
| `file-to-amps` | publishes the file's numbers |
| `amps-to-file` | writes the checkpoint's numbers into the file |

`--seqno.sender=N` and `--seqno.target=M` are each optional on the set
actions (the other keeps its value); `--seqno.session=FIX.4.2:A->B` limits to
one session. Two rules:

- **Stop the engine before a file action.** QuickFIX/J caches the numbers
  in memory and holds the file open; an edit under a running engine is
  overwritten by its next heartbeat. The compose wrapper refuses if the
  container is running.
- **An AMPS edit takes effect on the next start**, and only under
  `amps-wins` (or `highest`, if it is higher). Set both sides of the session
  consistently -- our next sender is their next target -- which
  `SeqnoFailoverIT`'s second test does for both engines before starting them.

## Running in containers

[`Containerfile`](Containerfile) puts the boot jar on a JRE and nothing
else: `/app/config`, `/app/data` and `/app/log` are mounts, the role is a
command argument, so the same image is the primary today and the DR
instance tomorrow. [`compose.yml`](compose.yml) runs three services:

| service | image | what it mounts |
| --- | --- | --- |
| `amps` | `$AMPS_IMAGE` (yours; there is no public one) | `../server/config/flows/quickfixj-dropcopy` → `/amps/config`, `deploy/amps` → `/amps/data` |
| `venue` | `localhost/quickfixj-v2-demo` | `config/` → `/app/config` (ro), `deploy/venue/{data,log}` → `/app/{data,log}` |
| `dropcopy` | `localhost/quickfixj-v2-demo` | `config/` → `/app/config` (ro), `deploy/dropcopy/{data,log}` → `/app/{data,log}` |

[`scripts/dropcopy-compose.sh`](scripts/dropcopy-compose.sh) is the entry
point (`build`, `start`, `stop`, `down`, `restart`, `status`, `logs`,
`failover`, `seqno`, `reset`, `printenv`): it creates the `deploy/`
folders, starts AMPS and waits for its own readiness line before starting
the engines, stops the engines before AMPS (so their final checkpoints
replicate), and adds the SELinux mount suffix where that applies. Host
ports are `QFJ_AMPS_PORT`/`QFJ_AMPS_WS_PORT`/`QFJ_AMPS_ADMIN_PORT`
(9007/9008/8085) and `QFJ_VENUE_PORT` (9876); the engines find AMPS and each
other by service name. `failover` removes the consumer container and starts
a new one with `QFJ_SEQNO_SOURCE=dropcopy-dr` on a **fresh, empty store
directory** (`data/store-dr-<stamp>`, passed as `QFJ_STORE_DIR` and remembered
in `deploy/dropcopy/.store-dir` so `seqno` and a later `start` use the same
one), leaving the primary's `data/store` in place as the lost disk; then it
prints the recovery line. A fresh directory rather than a rename, because
under podman machine a container started right after a rename on the host
can still be handed the old directory entry.

The `${NAME:default}` placeholders in `config/*/quickfixj.cfg` and
`config/*/<role>.yml` are how one config folder serves both the laptop
(`127.0.0.1`) and the compose network (`venue`, `amps`): the application
resolves them from a system property, then the environment, then the
default, before QuickFIX/J sees the file.

## The server side

[`server/config/flows/quickfixj-dropcopy/amps-config.xml`](../server/config/flows/quickfixj-dropcopy/amps-config.xml):

| topic | type | key | journalled | holds |
| --- | --- | --- | --- | --- |
| `sow/quickfixj/seqno` | json | `/sessionId` | yes | one checkpoint per session; the journal is its history |
| `sow/dropcopy/fix42/execs` | fix | `/17` | yes | the blotter: one record per ExecID, a resend overwrites |
| `dropcopy/fix42/audit` | fix | -- | yes | every routed message, as enriched |

## Tests

```bash
./gradlew :quickfixj-v2-demo:test                    # no AMPS: 66 tests
AMPS_IMAGE=<your image> \
  ./gradlew :quickfixj-v2-demo:integrationTest       # the failover, against a container
```

The unit suite includes `DropCopyPipelineTest` -- two real QuickFIX/J
engines over loopback with the replicator in memory: enrichment end to end,
both sides' checkpoints tracking the sessions, the failed-delivery
redelivery, and a `fix` destination forwarding onto a third session -- and
`DropCopyBootTest`, the Spring wiring started for real. The integration
suite **skips** when `AMPS_IMAGE` is unset, so `./gradlew build` stays green
on a machine without an image; see [amps-test-harness](../amps-test-harness/README.md).

## Things worth knowing

- **The role files are `venue.yml` and `dropcopy.yml`, not
  `application.yml`, on purpose.** Spring Boot loads every
  `config/*/application.yml` under the working directory by default, which
  merged both roles into every run until the tests caught it.
- **`NonStopSession=Y`** in both configs: a drop-copy session never resets
  its numbers by the clock, which is what makes replicating them worthwhile.
  A scheduled session would reset at its start time whatever the checkpoint
  said; the checkpoint carries the store's creation time so that decision
  stays QuickFIX/J's.
- **`include-outbound: true`** also routes what this engine sends. With a
  `fix` destination that would forward the forwards; the destination never
  echoes onto the session a message arrived on, but it does not know about
  loops through a third party.
- **QuickFIX/J 2.3.3 is the last 2.x**; 3.x moved the base to Java 17 and
  split the artifacts, and a sibling module on it is the natural next step.
