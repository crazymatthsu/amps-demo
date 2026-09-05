package com.demo.amps.seqno.subscribe;

import java.util.List;
import java.util.Map;

/**
 * What one run of the subscriber saw.
 *
 * @param processed         messages accepted and processed (in sequence or a
 *                          first message)
 * @param duplicatesSkipped messages at or below the mark, dropped
 * @param gaps              human-readable notes, one per detected gap
 * @param marks             the final high-water mark per sender
 */
public record SubscriberReport(int processed, int duplicatesSkipped, List<String> gaps,
                               Map<String, Long> marks) {

    public SubscriberReport {
        gaps = List.copyOf(gaps);
        marks = Map.copyOf(marks);
    }

    public boolean cleanRun() {
        return gaps.isEmpty();
    }
}
