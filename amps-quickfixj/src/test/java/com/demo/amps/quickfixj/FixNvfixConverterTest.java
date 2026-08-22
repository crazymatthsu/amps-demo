package com.demo.amps.quickfixj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixNvfixConverterTest {

    private static final char SOH = FixNvfixConverter.SOH;

    private static QuickFixDictionary dictionary;
    private static FixNvfixConverter converter;

    @BeforeAll
    static void loadDictionary() throws Exception {
        try (InputStream in = FixNvfixConverterTest.class.getResourceAsStream("/fix/FIX42-fixture.xml")) {
            dictionary = QuickFixDictionary.fromInputStream(in);
        }
        converter = new FixNvfixConverter(dictionary);
    }

    @Test
    @DisplayName("fromPath / fromXml load the same fields as fromInputStream")
    void loadersAgree(@TempDir Path tmp) throws Exception {
        String xml;
        try (InputStream in = FixNvfixConverterTest.class.getResourceAsStream("/fix/FIX42-fixture.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path file = tmp.resolve("FIX42-fixture.xml");
        Files.writeString(file, xml);

        QuickFixDictionary fromPath = QuickFixDictionary.fromPath(file);
        QuickFixDictionary fromXml = QuickFixDictionary.fromXml(xml);

        assertEquals("OrdStatus", fromPath.fieldByTag(39).orElseThrow().name());
        assertEquals(39, fromXml.fieldByName("OrdStatus").orElseThrow().number());
        assertEquals("Filled", fromPath.fieldByTag(39).orElseThrow().meaningOf("2").orElseThrow());
    }

    @Test
    @DisplayName("full FIX → NVFIX uses tag names and enum meanings")
    void fullFixToNvfix() {
        String fix = soh("35=8", "11=A1", "37=ORD-7", "17=EXEC-1", "20=0", "150=2", "39=2",
                "55=AAPL", "54=1", "38=500", "14=500", "151=0", "6=100.05");

        String nvfix = converter.fixToNvfix(fix);

        assertTrue(nvfix.startsWith("MsgType=ExecutionReport" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("ClOrdID=A1" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("OrdStatus=Filled" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("ExecType=Fill" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("Side=Buy" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("LeavesQty=0" + SOH), printable(nvfix));
        assertTrue(nvfix.charAt(nvfix.length() - 1) == SOH, "trailing SOH: " + printable(nvfix));
        assertFalse(nvfix.contains("39="), printable(nvfix));
    }

    @Test
    @DisplayName("full round-trip FIX → NVFIX → FIX restores tags and enum codes")
    void fullRoundTrip() {
        String fix = soh("35=8", "11=A1", "37=ORD-7", "17=EXEC-1", "20=0", "150=1", "39=1",
                "55=AAPL", "54=2", "38=500", "14=200", "151=300", "6=100");

        String back = converter.nvfixToFix(converter.fixToNvfix(fix));

        assertEquals(printable(fix), printable(back));
        assertEquals(fix, back);
    }

    @Test
    @DisplayName("partial FIX → NVFIX converts only the subset of tags present")
    void partialFixToNvfixSubset() {
        String partial = soh("11=A1", "55=AAPL", "54=1");

        String nvfix = converter.partialFixToNvfix(partial);

        assertEquals(soh("ClOrdID=A1", "Symbol=AAPL", "Side=Buy"), nvfix);
        assertFalse(nvfix.contains("MsgType="), "must not fill missing required fields");
        assertFalse(nvfix.contains("OrdStatus="), printable(nvfix));
        assertEquals(3, countFields(nvfix));
    }

    @Test
    @DisplayName("delta-shaped partial: only 11, 39, 151")
    void deltaPartialTags() {
        String delta = soh("11=PARENT-AAPL-2", "39=2", "151=0");

        String nvfix = converter.partialFixToNvfix(delta);
        assertEquals(soh("ClOrdID=PARENT-AAPL-2", "OrdStatus=Filled", "LeavesQty=0"), nvfix);

        String back = converter.partialNvfixToFix(nvfix);
        assertEquals(delta, back);
    }

    @Test
    @DisplayName("enum decode/encode accepts codes, identifiers, and descriptions")
    void enumDecodeEncode() {
        assertEquals(
                soh("OrdStatus=Filled"),
                converter.partialFixToNvfix(soh("39=2")));
        assertEquals(
                soh("39=2"),
                converter.partialNvfixToFix(soh("OrdStatus=Filled")));
        assertEquals(
                soh("39=2"),
                converter.partialNvfixToFix(soh("OrdStatus=2")),
                "already-coded NVFIX values encode as the same code");
        assertEquals(
                soh("39=1"),
                converter.partialNvfixToFix(soh("OrdStatus=PartiallyFilled")));
        assertEquals(
                soh("39=1"),
                converter.partialNvfixToFix(soh("OrdStatus=Partially filled")));
        assertEquals(
                soh("OrdStatus=PartiallyFilled"),
                converter.partialFixToNvfix(soh("39=1")));
    }

    @Test
    @DisplayName("unknown tags passthrough: kept as numeric keys, values unchanged")
    void unknownTagsPassthrough() {
        assertEquals(UnknownFieldPolicy.PASSTHROUGH, converter.unknownFieldPolicy());

        String fix = soh("11=A1", "9001=EVT-003", "9999=venue-x");
        String nvfix = converter.partialFixToNvfix(fix);

        assertTrue(nvfix.contains("ClOrdID=A1" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("9001=EVT-003" + SOH), "custom tag 9001 stays 9001: " + printable(nvfix));
        assertTrue(nvfix.contains("9999=venue-x" + SOH), printable(nvfix));
        assertEquals(fix, converter.partialNvfixToFix(nvfix));

        String unknownEnum = converter.partialFixToNvfix(soh("39=Z"));
        assertEquals(soh("OrdStatus=Z"), unknownEnum, "unknown enum code is not dropped");
        assertEquals(soh("39=Z"), converter.partialNvfixToFix(unknownEnum));
    }

    @Test
    @DisplayName("repeating group instances survive a round-trip")
    void repeatingGroupRoundTrip() {
        String fix = soh(
                "35=D",
                "11=A1",
                "55=AAPL",
                "54=1",
                "38=1000",
                "40=2",
                "78=2",
                "79=ACC-1",
                "80=400",
                "79=ACC-2",
                "80=600");

        String nvfix = converter.fixToNvfix(fix);
        assertTrue(nvfix.contains("NoAllocs=2" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("AllocAccount=ACC-1" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("AllocShares=400" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("AllocAccount=ACC-2" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("AllocShares=600" + SOH), printable(nvfix));
        assertEquals("Buy", extract(nvfix, "Side"));

        String back = converter.nvfixToFix(nvfix);
        assertEquals(printable(fix), printable(back));
        assertEquals(2, countOccurrences(back, "79="));
    }

    @Test
    @DisplayName("component-declared group (NoPartyIDs) is not dropped")
    void componentGroup() {
        String fix = soh(
                "35=D",
                "11=A1",
                "55=MSFT",
                "54=2",
                "453=1",
                "448=CLIENT-7",
                "447=D",
                "452=3");

        String nvfix = converter.fixToNvfix(fix);
        assertTrue(nvfix.contains("NoPartyIDs=1" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("PartyID=CLIENT-7" + SOH), printable(nvfix));
        assertTrue(nvfix.contains("PartyRole=ClientID" + SOH), printable(nvfix));
        assertEquals(fix, converter.nvfixToFix(nvfix));
    }

    @Test
    @DisplayName("partial group delta keeps instances without filling the rest of the message")
    void partialGroupDelta() {
        String delta = soh("78=1", "79=ACC-9", "80=50");
        String nvfix = converter.partialFixToNvfix(delta);
        assertEquals(soh("NoAllocs=1", "AllocAccount=ACC-9", "AllocShares=50"), nvfix);
        assertFalse(nvfix.contains("ClOrdID="));
        assertEquals(delta, converter.partialNvfixToFix(nvfix));
    }

    private static String soh(String... fields) {
        return String.join(String.valueOf(SOH), fields) + SOH;
    }

    private static String printable(String payload) {
        return FixNvfixConverter.printable(payload);
    }

    private static int countFields(String payload) {
        int n = 0;
        for (String token : payload.split(String.valueOf(SOH), -1)) {
            if (!token.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }

    private static String extract(String nvfix, String name) {
        String prefix = name + "=";
        for (String token : nvfix.split(String.valueOf(SOH), -1)) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        throw new AssertionError("missing " + name + " in " + printable(nvfix));
    }
}
