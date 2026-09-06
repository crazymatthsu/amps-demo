# 1. The problem, stated precisely

A publisher turns FIX messages into AMPS publishes on a transaction-logged
topic. Every message carries a **sender sequence number in tag 8888**.
Subscribers read the topic with bookmark subscriptions and keep their own
position in a bookmark store.

The requirement: **when the publisher loses its AMPS connection -- a network
drop, an AMPS restart, or its own crash -- it must find out the last tag 8888
that AMPS actually recorded, and republish everything after it. Nothing may be
lost and nothing may be published twice.**

That is the FIX resend-request problem, with AMPS in the role of the
counterparty that remembers what it received. This document pins down what
"last tag 8888" has to mean for the answer to be sufficient, before
[02](02-what-amps-already-does.md) looks at what AMPS provides and
[03](03-design-options.md) compares the ways to get the number.

## Who owns which state

| state | owner | durable form |
| --- | --- | --- |
| "what I decided to send", in 8888 order | the publisher | its **outbox**: an append-only log, one entry per 8888 (the analogue of a FIX engine's outgoing message store) |
| "what AMPS recorded" | AMPS | the **transaction log** (journal); plus the SOW, if the topic has one |
| "what I have processed" | each subscriber | its **bookmark store**, plus a per-sender high-water mark of tag 8888 |

Three things follow from the table, and the rest of the design rests on them.

1. **Tag 8888 is assigned before the message leaves the publisher's durable
   state.** The outbox entry is written first, then the publish happens. A
   crash between the two is the normal case the recovery has to handle, not
   an edge case. This is the same ordering a FIX engine uses: persist, then
   send.

2. **AMPS is the only authority on what AMPS has.** The publisher's own
   records say what it *tried* to send. The persisted acknowledgement says
   what arrived, but a crash can lose the ack after the message was written,
   and a connection drop can lose it before. Any recovery that infers "what
   AMPS has" from publisher-side state alone will, on some failure, either
   resend something AMPS already recorded or skip something it never got. So
   the number has to come from the server.

3. **The identity that AMPS tracks is the publisher's, not the message's.**
   AMPS stores a message once and delivers it to any number of subscribers;
   the question "what was the last thing *you* sent me?" only makes sense per
   sender. Here the sender is **tag 49 (SenderCompID)**. A gateway that
   multiplexes many FIX sessions onto one AMPS connection would key on its
   own identity instead (a user-defined tag such as 9999 PublisherID), with
   a sequence per gateway; nothing below changes.

## Why "the last 8888" is enough: the prefix invariant

The answer to "what did AMPS record from me?" is in general a *set*. It is a
single number only if what AMPS holds is always a **prefix** of the sender's
sequence: 8888 = 1, 2, ..., L with nothing missing and nothing repeated. Then
"the last one" and "the whole set" are the same fact, and the gap to
republish is exactly `(L, lastInOutbox]`.

The prefix invariant holds because of two ordering facts and one rule:

- **A publisher sends in 8888 order over a single TCP connection**, and AMPS
  processes a connection's commands in the order they arrive. If the
  connection dies while 8, 9 and 10 are in flight, AMPS may have recorded
  none, 8, 8 and 9, or all three -- but never 8 and 10 without 9.
- **A transaction-logged topic is written in processing order**, so the
  journal preserves that order.
- **Recovery only ever republishes from L+1 upward.** It never resends
  anything at or below L, because L came from the server.

Together: after every recovery the journal still holds an unbroken prefix.
The subscriber-side continuity check in
[05](05-subscriber-bookmarks-and-continuity.md) is a check on exactly this
invariant, which is what makes it useful as an audit rather than a second
mechanism.

One consequence worth stating: the invariant makes the *count* of the
sender's messages in the journal equal to L as well. The journal scan in
[03](03-design-options.md) uses that to verify the fast path.

## What "no duplicates" has to cover

Two different duplicates are easy to conflate:

| duplicate | where it appears | who prevents it |
| --- | --- | --- |
| **a second copy in the journal** -- the publisher republished something AMPS already had | AMPS, permanently; every subscriber and every replay sees it | the publisher, by only republishing above L; optionally AMPS itself, if the message carries an AMPS sequence number (see [02](02-what-amps-already-does.md)) |
| **a second delivery to one subscriber** -- the subscriber crashed after processing a message and before discarding its bookmark | that subscriber only, on resume | the subscriber, by checking 8888 against its per-sender high-water mark |

The first is the one this module is about; the second is what makes
subscriber processing at-least-once rather than exactly-once, and it exists
regardless of how well the publisher behaves. Both are handled, by different
parties, and the design keeps them separate on purpose.

## What is out of scope

- **Ordering across senders.** Tag 8888 is per sender. Two senders' messages
  interleave in the journal in arrival order and nothing here says anything
  about that.
- **AMPS replication and failover between instances.** If the publisher
  fails over to a replica that had not yet received the primary's tail, the
  replica's answer to "what do you have?" is the truth for the new primary
  and the recovery republishes accordingly. What the *old* primary does with
  its unreplicated tail when it rejoins is AMPS's replication contract, not
  this design's.
- **Message content.** The FIX messages here are 35=D new-order singles with
  enough tags to look real. Nothing about the recovery depends on the message
  type.
