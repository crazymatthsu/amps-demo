package com.demo.amps.cli.fix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.demo.amps.cli.CliOptions;
import com.demo.amps.cli.SowFixClient;
import com.demo.amps.common.fix.FixWire;
import org.junit.jupiter.api.Test;

/**
 * Conversion tests: no AMPS process, no network. Payloads are built as strings
 * the same way the native FIX demo puts them on the wire.
 */
class NvfixFormatterTest {

    private static final char SOH = (char) FixWire.SOH;

    @Test
    void expandsTagNumbersToNamesAndEnumMeanings() {
        String raw = "35=8" + SOH + "54=1" + SOH + "39=2" + SOH + "11=A1" + SOH;
        String nvfix = NvfixFormatter.toNvfix(raw);

        assertTrue(nvfix.contains("MsgType=ExecutionReport"), nvfix);
        assertTrue(nvfix.contains("Side=Buy"), nvfix);
        assertTrue(nvfix.contains("OrdStatus=Filled"), nvfix);
        assertTrue(nvfix.contains("ClOrdID=A1"), nvfix);
        assertFalse(nvfix.contains("35="), nvfix);
        assertEquals(SOH, nvfix.charAt(nvfix.length() - 1));
    }

    @Test
    void expandsAlreadyNamedNvfixValues() {
        String nvfixIn = "MsgType=D|Side=2|OrdStatus=0|OrderQty=500";
        String nvfix = NvfixFormatter.toNvfix(nvfixIn);

        assertEquals("MsgType=NewOrderSingle|Side=Sell|OrdStatus=New|OrderQty=500", nvfix);
    }

    @Test
    void leavesUnknownTagsAndFreeTextUnchanged() {
        String raw = "9999=xyz|58=rejected by venue|38=100";
        String nvfix = NvfixFormatter.toNvfix(raw);

        assertTrue(nvfix.contains("9999=xyz"), nvfix);
        assertTrue(nvfix.contains("Text=rejected by venue"), nvfix);
        assertTrue(nvfix.contains("OrderQty=100"), nvfix);
    }

    @Test
    void expandsExecTypePartialFillAndCancelReplace() {
        String raw = "35=G|150=1|20=0|40=2|59=3|434=2";
        String nvfix = NvfixFormatter.toNvfix(raw);

        assertTrue(nvfix.contains("MsgType=OrderCancelReplaceRequest"), nvfix);
        assertTrue(nvfix.contains("ExecType=PartialFill"), nvfix);
        assertTrue(nvfix.contains("ExecTransType=New"), nvfix);
        assertTrue(nvfix.contains("OrdType=Limit"), nvfix);
        assertTrue(nvfix.contains("TimeInForce=ImmediateOrCancel"), nvfix);
        assertTrue(nvfix.contains("CxlRejResponseTo=OrderCancelReplaceRequest"), nvfix);
    }

    @Test
    void rawOutputIsIdentity() {
        String payload = "35=8" + SOH + "11=A1" + SOH;
        assertEquals(payload, SowFixClient.format(payload, CliOptions.OutputFormat.RAW));
    }

    @Test
    void customEventIdTagUsesRepoName() {
        String raw = "9001=evt-1|35=F";
        String nvfix = NvfixFormatter.toNvfix(raw);
        assertTrue(nvfix.contains("EventId=evt-1"), nvfix);
        assertTrue(nvfix.contains("MsgType=OrderCancelRequest"), nvfix);
    }

    @Test
    void dictionaryCoversNativeDemoTags() {
        assertEquals("ClOrdID", Fix42Dictionary.nameOf(11));
        assertEquals("OrdStatus", Fix42Dictionary.nameOf(39));
        assertEquals("LeavesQty", Fix42Dictionary.nameOf(151));
        assertEquals(39, Fix42Dictionary.tagOf("OrdStatus"));
        assertEquals("Buy", Fix42Dictionary.meaningOf(54, "1"));
        assertEquals("500", Fix42Dictionary.meaningOf(38, "500"));
    }
}
