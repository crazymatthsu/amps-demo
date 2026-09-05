package com.demo.amps.seqno.publish;

import com.demo.amps.seqno.fix.FixTags;

/** The one content filter this module uses: "messages from this sender". */
final class SenderFilter {

    private SenderFilter() {
    }

    /**
     * A filter on tag 49. AMPS parses the fix payload natively, so the filter
     * references the tag by number, and the value is single-quoted -- FIX
     * values are text on the wire. The sender is validated on the way in, so a
     * quote in it is a programming error, not user input; reject it rather
     * than build a filter that means something else.
     */
    static String forSender(String sender) {
        if (sender.indexOf('\'') >= 0) {
            throw new IllegalArgumentException("sender must not contain a quote: " + sender);
        }
        return "/" + FixTags.SENDER_COMP_ID + " = '" + sender + "'";
    }
}
