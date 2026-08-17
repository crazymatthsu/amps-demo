# docs

Prose, kept in the build so it cannot rot silently: `./gradlew :docs:check` fails
if a required document is missing or a relative link points at a file that does not
exist.

| document | read it for |
| --- | --- |
| [runbook.md](src/runbook.md) | how to run everything, and what to do when it breaks |
| [amps-vs-kafka.md](src/amps-vs-kafka.md) | what actually differs, and when each is the right choice |
| [sow-and-recovery.md](src/sow-and-recovery.md) | SOW, snapshots, query-and-subscribe, restart survival |
| [transaction-log-sizing.md](src/transaction-log-sizing.md) | keeping the journal small; retention and expiry settings |
| [high-volume-market-data.md](src/high-volume-market-data.md) | worked case: 500 GB/day of market data on a 100 GB disk |
| [delta-updates.md](src/delta-updates.md) | delta publish/subscribe semantics and the traps |
| [protobuf-json-and-amps.md](src/protobuf-json-and-amps.md) | why protobuf schema + JSON wire, and the rules it imposes |
| [fix-order-state.md](src/fix-order-state.md) | FIX 4.2 order state: what AMPS derives, what a gateway must |
| [native-fix-and-nvfix.md](src/native-fix-and-nvfix.md) | raw FIX/NVFIX message types: native keys, filters, journal |

Suggested order for someone new to AMPS: **runbook** to get an instance up, then
**amps-vs-kafka** for the mental model, then **sow-and-recovery**. The rest are
reference material for when you hit the relevant problem.
