# amps-ha-demo

Two AMPS instances replicating to each other, a publisher and a consumer that
survive losing either one, and a test that proves it: kill an instance while
messages are in flight and every message still arrives, once, in order.

```
amps-ha-demo/
├── config/primary/amps-config.xml     instance "amps-ha-primary"
├── config/secondary/amps-config.xml   instance "amps-ha-secondary" (same, mirrored)
├── compose.yml                        both instances on one network, in podman
├── scripts/ha-compose.sh              start / failover / revive / status / validate
├── src/main/java/com/demo/amps/ha/    HAClient publisher + consumer, and the demo main
└── src/integrationTest/java/          the failover on Testcontainers
```

## Run it

You need an AMPS image (there is no public one; see
[server/Containerfile](../server/Containerfile)) and podman or docker.

```bash
export AMPS_IMAGE=amps-demo:5.3.5.135          # whatever you tagged yours
./amps-ha-demo/scripts/ha-compose.sh start     # both instances, waits for the replication link
./gradlew :amps-ha-demo:run --args="both"      # publisher + consumer in one JVM, a minute of traffic
```

While the demo is publishing, in another shell:

```bash
./amps-ha-demo/scripts/ha-compose.sh failover          # SIGKILL the primary
./amps-ha-demo/scripts/ha-compose.sh revive primary    # bring it back; it catches up
./amps-ha-demo/scripts/ha-compose.sh kill secondary    # and the clients move again
```

The demo's log shows both clients noticing the loss, redialling the other
instance, the publisher replaying what had not been acknowledged and the
consumer resubscribing from its bookmark. It ends with a verdict:

```
VERDICT: no message lost, none duplicated, order preserved
  published 3000, unacknowledged 0
  consumer received 3000 of 3000 (highest 3000), 0 duplicates, 0 out of order, no gaps
  publisher failed over 2 time(s), consumer failed over 2 time(s)
  publisher logons [tcp://127.0.0.1:9007/amps/json, tcp://127.0.0.1:9107/amps/json, tcp://127.0.0.1:9007/amps/json]
  consumer logons [tcp://127.0.0.1:9007/amps/json, tcp://127.0.0.1:9107/amps/json, tcp://127.0.0.1:9007/amps/json]
```

Two things in that log are easy to misread as trouble and are not:

- `sequence ... dropped by the server as a duplicate (already there, expected
  after a replay)` -- the publisher replayed its store to the survivor, and
  the messages that had been replicated before the crash (but whose
  acknowledgment died with the primary) were recognised by client name and
  sequence number and dropped. That is the server-side half of exactly-once.
- the consumer's count **stalls for about six seconds after a kill** while the
  publisher's `unacknowledged` climbs, then both catch up at once. That is the
  survivor waiting for its synchronous peer, then the scheduled action
  downgrading the link (see below). Nothing is lost during the stall; it is
  the price of the guarantee, and how long it lasts is a configuration choice.

and exits non-zero if the counts say otherwise. `publisher` and `consumer`
run the halves in separate JVMs; settings are `-Dha.*` system properties or
`HA_*` environment variables (`ha.uris`, `ha.topic`, `ha.count`,
`ha.intervalMs`, `ha.publisher`, `ha.consumer`, `ha.bookmark`, `ha.stateDir`,
`ha.run` -- see [`HaSettings`](src/main/java/com/demo/amps/ha/HaSettings.java)).
`-Dha.stateDir=data` switches to file-backed publish and bookmark stores, so
the demo process itself can be killed and restarted and still lose nothing.

Host ports: primary `9007`/`9008`/`8085`, secondary `9107`/`9108`/`8185`
(client / websocket / admin). The admin consoles at
`http://127.0.0.1:8085/` and `http://127.0.0.1:8185/` show each instance's
view of the replication link.

## The test

```bash
export AMPS_IMAGE=amps-demo:5.3.5.135
# podman: Testcontainers needs the Docker-compatible socket, and no reaper
export DOCKER_HOST="unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')"
export TESTCONTAINERS_RYUK_DISABLED=true
./gradlew :amps-ha-demo:integrationTest
```

[`FailoverIT`](src/integrationTest/java/com/demo/amps/ha/FailoverIT.java)
starts both instances as Testcontainers on one network -- with the network
aliases `amps-primary` and `amps-secondary`, so the very same config files
serve compose and the test -- and then takes the pair apart in stages while a
publisher numbers messages `1..N` and a consumer keeps a
[`SequenceLedger`](src/main/java/com/demo/amps/ha/SequenceLedger.java):

| stage | what happens | what is asserted |
| --- | --- | --- |
| 1 | publishing `1..600`; at `200` the **primary is SIGKILLed** | every number `1..600` received once, in order; both clients now logged on to the secondary; the secondary's SOW holds all 600 |
| 2 | the primary is **started again on its own data** and catches up, and the secondary's link to it is **upgraded back to sync**; publishing `601..1000`; at `800` the **secondary is SIGKILLed** | every number `1..1000` received once, in order; both clients back on the primary; the primary's SOW holds all 1000, including what was published to the secondary while the primary was dead |
| 3 | a **brand-new consumer** replays the topic from the epoch | exactly `1..1000`, once, from the survivor's journal |

SIGKILL rather than a stop, because a stop lets AMPS flush and say goodbye to
its peer; a crash does not, and the crash is the case the clients' stores
exist for. The instances' data lives in each container's writable layer, so a
run never inherits the last run's journal but a revived instance does find
what its killed self left behind.

The suite is skipped, with the reason printed, when `AMPS_IMAGE` is unset or no
Docker API is reachable -- so `./gradlew build` stays green on a machine without
AMPS, and a green build is not by itself proof this ran. Under emulation on
Apple Silicon the whole thing takes a few minutes, most of it AMPS starting.

## What makes it lossless

Neither the server nor the client does this alone. Five things, each necessary:

**1. Synchronous replication, both ways.** Each instance lists the other as a
`<Destination>` with `<SyncType>sync</SyncType>`. AMPS then withholds the
*persisted* acknowledgment for a publish until the other instance has the
message too. Whichever instance dies, every message the publisher was told is
safe exists on the survivor. The two instances share a `<Group>`, which is how
AMPS is told they are interchangeable, and `<PassThrough>.*</PassThrough>`
makes each forward what it received from the other, so the pair stays a full
mirror whichever instance a publisher happened to connect to. Replication
replays the transaction log, so a replicated topic must be journaled, and a
SOW topic wants `<Durability>persistent</Durability>` so a revived instance
recovers its own state from disk before receiving the rest from its peer.

**2. A publish store on the publisher.** `HAClient` with a `PublishStore`
(file) or `MemoryPublishStore`. Every publish is written to the store before
it is sent and removed only on the persisted acknowledgment; after a reconnect
the client replays what is still there. A replayed message the survivor had in
fact already received (replicated before the crash, acknowledgment lost with
it) is dropped by the server as a duplicate of the same **client name** and
**sequence number**, and reported through the `FailedWriteHandler` with reason
`Duplicate` -- an expected line in the log after a failover, not an error. The
client name is therefore the identity everything hangs off: stable across runs,
unique among connected clients.

**3. A bookmark store on the consumer.** `HAClient` with a
`LoggedBookmarkStore` (file) or `MemoryBookmarkStore`, and a bookmark
subscription. After a reconnect the client re-issues the subscription from the
store's most recent position, and the store filters redelivery of anything it
has already logged. Bookmarks survive replication unchanged -- a message keeps
the bookmark of the instance that first accepted it -- so the position held on
the dead instance means the same thing on the survivor.

**4. `fully_durable` on the subscription.** This is the one that is easy to
miss. By default AMPS delivers a message to a subscriber as soon as the local
instance has journaled it, which can be *before* the other instance has it.
Kill the instance in that window and the consumer's newest bookmark names a
message the survivor never received: it cannot resume from it, and when the
publisher replays the same message the survivor gives it a *new* bookmark, so
the bookmark store cannot recognise it either -- a duplicate at best. With
`fully_durable` a message is delivered only once every synchronous destination
has acknowledged it, so every bookmark the consumer ever holds exists on both
instances. The cost is a replication round trip of latency.

**5. Downgrade the link when the peer is gone.** The flip side of sync: while
one instance is down, the survivor acknowledges nothing (it is waiting for a
peer that will not answer), publish stores grow, `publishFlush` never returns,
and a `fully_durable` consumer receives nothing. The documented remedy, and the
configs' `<Actions>` block, is a scheduled `amps-action-do-downgrade-replication`
that switches the link to async acknowledgment once the destination has been
unresponsive for longer than an outage threshold, and an
`amps-action-do-upgrade-replication` that restores sync once it is back and
caught up. The demo uses seconds so a failover is watchable; production would
use minutes, longer than the time at which monitoring already knows the
instance is gone -- because a downgraded link is unsafe to fail over *to*, and
messages acknowledged while downgraded exist on one instance until the peer
returns.

## Two client behaviours worth knowing

- **`publish` blocks through a failover.** With retry-on-disconnect (the
  default) the HA client runs the reconnect on the publishing thread and then
  carries on; the publisher loop does not see the outage except as a pause. If
  a `DisconnectedException` does escape, the message is already in the store
  and must **not** be republished -- a second publish would store it again
  under a new sequence number, which the server cannot recognise as a
  duplicate. [`OrderPublisher`](src/main/java/com/demo/amps/ha/OrderPublisher.java)
  logs and moves on.
- **`publishFlush`, not `flush`.** `Client.flush(long)` is deprecated in this
  client version and its body does nothing; `publishFlush` waits for the
  publish store to drain, which with sync replication means "on both instances"
  -- or, while one is down, "acknowledged by the survivor after the downgrade".

## Reading the instance configs

The two files are identical except for `<Name>`, the Destination `<Name>`, and
the Destination `<InetAddr>` (`amps-secondary:9010` vs `amps-primary:9010`).
The interesting elements:

| element | why |
| --- | --- |
| `<Group>amps-ha-demo</Group>` | same on both: they are declared equivalent, and a Destination's Group must match the remote's |
| `<Transport><Type>amps-replication</Type><InetAddr>9010</InetAddr>` | the incoming replication transport; one per instance, replication only |
| `<Replication><Destination>` | the outgoing link: `Topic` (name is a regex), `Group`, `SyncType`, `PassThrough`, `Transport` |
| `<TransactionLog><Topic>` | replicated topics must be journaled |
| `<Actions>` | the scheduled downgrade/upgrade described above |

`scripts/ha-compose.sh validate` runs the server's own `--verify-config` on
both. The build parses them as XML on every run (`checkConfigXml`): AMPS
rejects a double hyphen inside an XML comment, and these files carry long
comments.

## What the instances log

The lines worth grepping for, as AMPS 5.3.5.135 words them (`ha-compose.sh
status` prints the last few, `logs` prints them all):

| moment | line |
| --- | --- |
| the peer connected to this instance's replication transport | `AMPS replication client session logon: amps-ha-primary!amps-ha-secondary@amps-ha-demo!sync!amps-replication` |
| this instance finished sending its backlog to the peer | `AMPS transaction log replay completed for replication client: ...` |
| the peer died | `AMPS client session disconnect: ...!sync!amps-replication` |
| the scheduled action gave up waiting for it | `AMPS replication destination amps-ha-primary downgraded from sync to async due to lack of activity in past 6 seconds` (logged at `error`) |
| a killed instance restarting | `AMPS transaction log journal recovery completed`, then `AMPS replication client ack info recovery starting at lowest replication acked txid = ... and last tx log txid = ...` -- it knows what the peer had acknowledged, so it knows what to send and what to expect |
| the peer is back | `AMPS replication client session logon reestablished: ...`, `AMPS replication resync started for replication client: ...` |
| the link is trusted again | `AMPS replication destination ...!sync!amps-replication upgraded from async to sync` |
| the peer is gone from DNS | `AMPS replication destination unable to resolve specified InetAddr: "amps-secondary:9010" ... host_not_found` -- a killed compose container loses its name on the network; the instance keeps retrying and reconnects when `revive` brings it back |

The link name reads `<source>!<destination>@<group>!<sync type>!<transport>`,
so `amps-ha-secondary!amps-ha-primary@...` in either instance's log is the
secondary-to-primary direction.

In the test run behind this README the sequence was: kill at `t`; both
clients logged on to the survivor at `t+0.3s`; survivor downgraded its link
at `t+6s`; the publisher's 430 unacknowledged messages drained and the
consumer caught up within the following second; revived instance recovered
its journal and SOW in well under a second, received the backlog, and the
survivor upgraded the link back to sync 3s later.

## Compose stack

[compose.yml](compose.yml) is the target of the script, not a second entry
point: the script creates the bind-mounted `deploy/<instance>/` folders, waits
for each instance's `initialization completed` line rather than for an open
port (the port forwarder accepts connections before AMPS is ready), and then
for the replication link. `kill` uses the engine's `kill --signal SIGKILL`;
`revive` uses `start` and waits for a *new* ready marker, since the old one is
still in the log. `reset` deletes `deploy/`, both instances' SOW and journal.
