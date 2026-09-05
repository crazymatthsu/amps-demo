# 4. The chosen design, and what it survives

## Decision

| decision | choice | why |
| --- | --- | --- |
| how the publisher learns L | **A1 SOW lookup**, verified by **A2 journal scan** ([03](03-design-options.md)) | both answer in the tag-8888 space from the server's own records, need no client-side file, and are plain commands this repository already runs against a live instance. The scan catches what the lookup cannot: a regressed or reset SOW. |
| how the demo publisher sends | **B1 plain client**, application-level recovery | it is the mechanism under study, and every step of it is observable. Nothing the library does in the background can be mistaken for the design working. |
| what production should do | **B3**: the same recovery *plus* a file-backed publish store | the store covers transient disconnects without a query and lets the server reject any overlap by sequence number; the recovery covers the store being lost. Neither alone covers both. |
| the publisher's identity | tag 49 (SenderCompID), and the AMPS client name derived from it | AMPS keys its per-publisher state on the client name; the SOW keys the checkpoint on the sender. Both must be stable across restarts. |
| where 8888 comes from | the outbox assigns it: `last + 1`, written to the outbox before the publish | assigning before durably recording it would let a crash reuse a number. |

The logon-ack answer (A3) is deliberately not in the loop: it would need the
AMPS sequence number to be tag 8888, which needs a custom `Store` whose
contract cannot be validated without a live server. It is the natural next
step once one is available, and would turn the SOW lookup into a fallback.

## The recovery procedure

Run on every connect, including the very first one (a first run is a
recovery from an empty journal and needs no special case).

1. **Connect with the stable client name** and enable heartbeats. A
   transaction-logged instance permits one connection per name; after a
   crash the old session lingers until the server's heartbeat timeout
   notices it, so `NameInUse` is retried with a delay rather than treated as
   an error.
2. **Flush.** `publishFlush` returns once the server has processed
   everything this connection sent so far. Fresh after a connect there is
   nothing, but with a publish store in play (B3) the logon has just
   replayed the store's contents, and L must be read *after* they landed.
3. **Look up L.** `sow` on the topic with filter `/49 = '<sender>'`. Zero
   records means L = 0 as far as the SOW knows.
4. **Verify L.** Bookmark-subscribe with the same filter from a timestamp
   `lookback` before now (from the epoch if the SOW said 0), read to the end
   of the journal, and check that the 8888 values seen are contiguous, that
   their maximum matches the SOW's answer, and that none repeats. This step
   is what turns "the SOW says 12" into "AMPS holds exactly 1..12".
5. **Reconcile** the three numbers -- `L_sow`, `L_journal`, and the outbox's
   `last`:

   | observation | meaning | action |
   | --- | --- | --- |
   | `L_journal == L_sow` | normal | L is that number |
   | `L_journal > L_sow` | the SOW is behind the journal: a publisher resent an older message, or the SOW file was reset | L = `L_journal`; alarm, because a publisher broke the invariant |
   | `L_journal < L_sow` | the scan window missed older messages: journal aged out past the lookback, or the lookback is too short | L = `L_sow`; note the verification was partial, and widen the lookback if it matters |
   | `L > last` | AMPS holds messages the outbox does not know about | **stop.** The outbox was lost or truncated; publishing would reuse numbers. This needs a person. |

6. **Republish the gap** `(L, last]` from the outbox, in order, then flush.
7. **Read L again** and require `L == last`. Only then start publishing new
   messages.

Steps 3 and 4 both work on a topic that is only journalled: the lookup is
then simply skipped and the scan is the answer. The SOW key is an
optimisation that makes the common case one keyed read, not a requirement.

### Why the scan starts from a timestamp

A bookmark subscription can start at a bookmark, at the epoch, or at a
timestamp (`YYYYMMDDTHHMMSS`). The publisher does not hold a bookmark for
its own messages -- the persisted ack carries a sequence number, not a
bookmark -- so the scan starts from the wall clock: now minus a lookback
that comfortably exceeds the longest outage the publisher is expected to
have. When the SOW reports nothing at all, the scan starts from the epoch
instead, because the choice is then between "genuinely a new sender" and
"the SOW was reset", and only the whole journal can tell them apart.

## Components

| class | role |
| --- | --- |
| `Outbox` | append-only file of `8888 -> FIX payload`, one line each; assigns the next 8888; replays any suffix. The publisher's system of record. |
| `SequencedPublisher` | the publish path: `outbox.append` then `client.publish`; exposes "stop after K sends" so a crash can be simulated deterministically. |
| `LastSequenceLocator` | the question "what is the last 8888 AMPS has for this sender?" with two answers: `SowLastSequenceLocator` (A1) and `JournalLastSequenceLocator` (A2, which also reports the contiguity check). |
| `GapRecovery` | the pure decision from step 5: given the two answers and the outbox, what to republish or why to stop. Unit-tested without a server. |
| `PublisherRecovery` | steps 1 to 7 wired together against a live client. |
| `BookmarkSubscriber`, `SequenceTracker` | the subscriber side: [05](05-subscriber-bookmarks-and-continuity.md). |
| `SeqnoDemo` | the phases below, and the guided `all` sequence. |

## Failure matrix

`sent` is the highest 8888 the publisher pushed onto a socket; `last` is
the highest in the outbox; L is what AMPS holds.

| failure | L vs. the outbox afterwards | how it is found | recovery |
| --- | --- | --- | --- |
| publisher crashes **between outbox append and send** | `L = sent < last` | SOW lookup (verified by scan) | republish `(L, last]` |
| publisher crashes **after send, before the persisted ack** | `L` is `sent`, or less if the socket buffer had not drained | same | same; the number of messages actually lost is whatever the server did not get, and the publisher never needed to know it |
| **network drop** mid-batch | as above | same, on reconnect | same |
| **AMPS restarts** (clean stop, or killed) | the journal is recovered on the way up; L is what was journalled | same, once the server is back; `NameInUse` until the old session is reaped | same |
| publisher restarts **on another host** with the outbox but no client-side files | `L` as recorded; the outbox knows `last` | same | same. This is the case a publish store alone cannot cover. |
| outbox lost, AMPS intact | `L > last` (outbox says less than AMPS has) | reconciliation step 5 | **stop and alert.** Numbers must not be reused. The outbox has to be rebuilt from the journal before publishing resumes. |
| journal aged out beyond the lookback | scan sees fewer than the SOW | reconciliation | trust the SOW, flag partial verification |
| SOW file reset, journal intact | SOW says 0, scan from epoch says `L` | reconciliation | trust the scan; alarm |
| a buggy publisher **resends below L** (the "naive" phase) | journal gains duplicates; the SOW's last write may regress | the scan sees repeats; subscribers' 8888 check sees them | with a publish store the server would have rejected them; without one the subscribers drop them, and the journal keeps the evidence |
| subscriber crashes between processing and bookmark discard | not a publisher failure | the subscriber's per-sender high-water mark | redelivered message skipped as a duplicate |

## What the demo shows

`./gradlew :Claude-code:fix-pub-seqno:run --args="all"` runs, against one
instance and with state under `build/client-state/fix-pub-seqno/`:

1. **publish** -- 8888 = 1..N go out and are acknowledged; L is read back
   both ways and equals N.
2. **crash** -- M more messages are appended to the outbox but only K of
   them are sent; the connection is dropped without a flush. This is a
   crash between "persisted locally" and "sent", the FIX engine's classic
   gap.
3. **recover** -- a new client with the same name finds L = N + K by lookup,
   confirms it by scan, republishes the M - K missing messages from the
   outbox, and reads back L = N + M.
4. **subscribe** -- a subscriber with a bookmark store resumes from where it
   left off and reports every 8888 as in sequence: no gap, no repeat.
5. **naive** -- a second sender resends part of its history without asking
   AMPS first; the scan reports the repeats and the subscriber drops them.

Each phase is also runnable alone, so the crash and the recovery can be
separated by a server restart, a `podman kill`, or a coffee.

The integration test (`FixPubSeqnoIT`) drives the same phase code against
a throwaway container and asserts the numbers.

## Production notes

- **Use B3.** Add a file-backed `PublishStore` to the publisher and keep
  this recovery. Install a `FailedWriteHandler`: with the store in place the
  server rejects any overlap and the handler is the only place that reports
  it.
- **Client name.** One stable name per sender, derived from tag 49. Never
  a hostname or a random suffix, or the server has nothing to correlate.
- **Heartbeats.** Set them on the publisher so a dead session is reaped in
  seconds, not at the TCP keepalive horizon; the reconnect otherwise waits
  on `NameInUse` for the difference.
- **Retention.** The journal must retain at least the scan lookback, and
  subscribers must be able to resume from their oldest bookmark; both are
  retention decisions, covered in
  [transaction-log-sizing.md](../../../docs/src/transaction-log-sizing.md).
- **One connection at a time per sender.** Two publishers with the same
  sender identity would each be right about their own outbox and wrong about
  the other's; the client-name rule enforces this for AMPS connections, and
  the deployment has to enforce it for the outbox.
