# Claude-code

Demo modules developed in Claude Code sessions. Each is a Gradle subproject
in its own folder, with its own README and design notes, and follows the
conventions of the rest of the repository: the AMPS instance comes from
`server/scripts/amps.sh` with a business flow under `server/config/flows/`,
unit tests run in `./gradlew build`, and integration tests start a throwaway
container through `amps-test-harness` and skip when `AMPS_IMAGE` is unset.

| module | question it answers |
| --- | --- |
| [fix-pub-seqno](fix-pub-seqno/README.md) | a FIX publisher loses its AMPS connection: how does it find the last sender sequence number (tag 8888) AMPS recorded, and republish the gap with no loss and no duplicate? |
