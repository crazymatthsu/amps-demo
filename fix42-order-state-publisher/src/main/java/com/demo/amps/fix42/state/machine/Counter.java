package com.demo.amps.fix42.state.machine;

/** The machine's counters (docs/04 §7); {@link MachineCounters} holds the values. */
public enum Counter {
    APPLIED, CREATED, CHANGED,
    IGNORED_DUPLICATE, IGNORED_STALE, IGNORED_LATE, IGNORED_ABSORBED, IGNORED_LATE_FILL,
    IGNORED_REQUEST, IGNORED_UNROUTED, IGNORED_KEY_CONFLICT, IGNORED_UNMATCHED, IGNORED_NO_ID,
    IGNORED_HOOK,
    MIDSTREAM_CHAINS, MIDSTREAM_WITH_KNOWN_ORDER_ID, LATE_DS,
    UNMATCHED_REJECTS, UNMATCHED_CONFIRMS, INFERRED_REPLACE_ANSWERS, INFERRED_REQUESTS,
    REQUEST_REJECTS_AS_REPORTS, REJECT_TYPE_MISMATCH,
    LATE_FILLS, REOPENED_BY_VENUE, TERMINAL_FROM_REJECT, LATE_IOC_ACK, REOPEN_UNDECIDABLE,
    INCONSISTENT_REPORTS,
    ID_CONFLICTS, KEY_COLLISIONS, STRIPPED_TAGS;

    /** The camelCase name docs/04 §7 uses, for the summary log line. */
    public String label() {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : name().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? c : Character.toLowerCase(c));
                upper = false;
            }
        }
        return out.toString();
    }
}
