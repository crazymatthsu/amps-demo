package com.demo.amps.qfj2.seqno;

/**
 * What a starting engine does when its file store and the AMPS checkpoint
 * disagree. Bound from {@code qfj.seqno.recovery} ({@code amps-wins},
 * {@code file-wins}, {@code highest}).
 */
public enum RecoveryPolicy {
    /**
     * Take AMPS's numbers and write them to the file. The failover case: a
     * DR box whose disk is empty or stale. The default.
     */
    AMPS_WINS,
    /**
     * Trust the file and publish it to AMPS. A primary that knows its own
     * disk is right -- or a site that wants replication to be observe-only.
     */
    FILE_WINS,
    /**
     * Take the higher of each number. A primary restarting after a crash that
     * may have lost the last in-flight replication, where "never go backwards"
     * is the safer bet than either source alone.
     */
    HIGHEST
}
