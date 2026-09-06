package com.demo.amps.seqno.publish;

/**
 * What a recovery run did: the decision it reached, how many messages it
 * republished, and the last sequence number AMPS held when it finished.
 *
 * @param decision       the reconciliation outcome that drove the run
 * @param messagesResent how many outbox entries were re-sent
 * @param finalL         the last 8888 AMPS reported after the republish and
 *                       re-verification; equals the outbox's last sequence on
 *                       a healthy recovery
 * @param verified       true when {@code finalL} matched the outbox's last
 *                       sequence, i.e. AMPS and the publisher agree afterwards
 */
public record RecoveryReport(RecoveryDecision decision, int messagesResent,
                             long finalL, boolean verified) {
}
