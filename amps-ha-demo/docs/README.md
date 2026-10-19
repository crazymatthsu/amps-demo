# amps-ha-demo -- design notes

Answers to the questions a replicated pair raises once the demo works, written
up from 60East's documentation, the AMPS Java client sources and this module's
own configs. The [module README](../README.md) is the run guide and the proof;
these are the reference for why the pieces are shaped the way they are.

| document | what it settles |
| --- | --- |
| [replication-and-the-transaction-log.md](replication-and-the-transaction-log.md) | why a replicated SOW topic must be journaled (replication replays the journal; the SOW file is never copied), what a SOW does and does not need the journal for, how `Durability` and the journal combine at startup, and why the journal is never compacted but only aged out in whole files, with replication as a veto on the ageing rather than a trigger for it -- and what all of that costs on disk for this pair |
| [what-a-bookmark-is.md](what-a-bookmark-is.md) | what the value of a bookmark is (`publisherId|sequence|`, a logical position in an instance's transaction log, not a file offset), why it survives replication and retention when an offset could not, the special values (`0`, `0|1|`, `recent`, timestamps, lists, ranges), and the two different "sequence numbers" this module relies on |

A note on what is verified, which each document repeats for itself: statements
about the AMPS **client** library are read from the `amps-client` 5.3.5.3
sources; statements about the AMPS **server** are 60East's documented
behaviour and were not re-verified against a live instance in the session that
wrote them. The module's integration test is where the end-to-end behaviour is
checked against real instances.
