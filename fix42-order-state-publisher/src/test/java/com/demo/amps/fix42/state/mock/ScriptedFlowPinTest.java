package com.demo.amps.fix42.state.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the scripted flow to the one {@code fix42-publisher}'s mock venue
 * produces.
 *
 * <p>This module's mock venue is a copy, not a dependency (docs/05 phase 0),
 * so nothing stops the two from drifting. The hash below was taken from
 * {@code fix42-publisher}'s {@code MockFixFlow} at copy time; as long as this
 * copy renders the same bytes, the end state of the ten ordinary chains can be
 * compared across the two blotters. Phase 1 extends {@code OrderChain} with
 * several requests in flight and venue-dialect switches, and the default
 * dialect must keep rendering this flow byte for byte.
 *
 * <p>If this fails because the flow was changed <i>on purpose</i>, change both
 * modules' flows and take the new hash from {@code fix42-publisher}'s.
 */
class ScriptedFlowPinTest {

    /** SHA-256 over every message's rendered bytes, each followed by a newline. */
    private static final String FIX42_PUBLISHER_FLOW_SHA256 =
            "ff1d565f677a0bd0b08a1f6a604cfce4bccf5b2f2a1a863544e7c87149f05e94";

    @Test
    @DisplayName("the scripted flow renders byte for byte as fix42-publisher's does")
    void scriptedFlowMatchesThePin() throws Exception {
        assertThat(sha256(MockFixFlow.events())).isEqualTo(FIX42_PUBLISHER_FLOW_SHA256);
    }

    @Test
    @DisplayName("the flow is the ten chains, in their fixed order")
    void tenChains() {
        assertThat(MockFixFlow.chains()).extracting(OrderChain::chainId).containsExactly(
                "PARENT-AAPL", "PARENT-MSFT", "PARENT-GOOG", "PARENT-NVDA", "PARENT-TSLA",
                "CHILD-TSLA-A", "CHILD-TSLA-B", "PARENT-AMZN", "PARENT-META", "PARENT-NFLX");
    }

    static String sha256(List<FixEvent> events) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (FixEvent event : events) {
            digest.update(event.message().render().getBytes(StandardCharsets.ISO_8859_1));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
