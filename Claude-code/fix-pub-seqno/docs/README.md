# fix-pub-seqno — design notes

The design worked out before the code, and the reference for why each piece is
shaped the way it is. Read in order for the argument; jump to
[04](04-chosen-design-and-failure-matrix.md) for the decision and the failure
matrix.

| # | document | what it settles |
| --- | --- | --- |
| [01](01-problem-and-invariants.md) | The problem, stated precisely | who owns which state, why "the last tag 8888" is a sufficient answer (the prefix invariant), and the two different duplicates that must not be confused |
| [02](02-what-amps-already-does.md) | What AMPS already does | the client library's publish store, sequence numbers and duplicate rejection — read from the 5.3.5.3 source — and the one case it does not cover |
| [03](03-design-options.md) | Design options | four ways to find L and three ways to publish, compared |
| [04](04-chosen-design-and-failure-matrix.md) | The chosen design | the decision, the seven-step recovery procedure, the failure matrix, and the production recommendation |
| [05](05-subscriber-bookmarks-and-continuity.md) | The subscriber | bookmark resume, and what the per-sender tag-8888 check adds on top of it |

A note on what is verified. The statements about the AMPS **client** library
(publish store behaviour, sequence assignment, logon-ack handling) were read
directly from the `com.crankuptheamps:amps-client` 5.3.5.3 sources and are
cited as such. The statements about the AMPS **server** (duplicate rejection,
the logon acknowledgement's contents, journal retention) are 60East's
documented behaviour; they were not re-verified against a live instance in the
session that wrote these notes, and where a claim is load-bearing the text
says so. The integration test is where the end-to-end behaviour is checked
against a real instance.
