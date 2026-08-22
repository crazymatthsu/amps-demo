package com.demo.amps.quickfixj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeltaPublishBuilderTest {

    private static final char SOH = FixNvfixConverter.SOH;

    private static QuickFixDictionary dictionary;

    @BeforeAll
    static void loadDictionary() throws Exception {
        try (InputStream in = DeltaPublishBuilderTest.class.getResourceAsStream("/fix/FIX42-fixture.xml")) {
            dictionary = QuickFixDictionary.fromInputStream(in);
        }
    }

    @Test
    @DisplayName("mixed FIX tags and NVFIX names on one builder")
    void mixedFixAndNvfixKeys() {
        String nvfix = new DeltaPublishBuilder(dictionary)
                .set(11, "PARENT-AAPL-2")
                .set("OrdStatus", "Filled")
                .set("LeavesQty", "0")
                .buildNvfix();
        assertEquals(soh("ClOrdID=PARENT-AAPL-2", "OrdStatus=Filled", "LeavesQty=0"), nvfix);

        String fix = new DeltaPublishBuilder(dictionary)
                .set("ClOrdID", "PARENT-AAPL-2")
                .set(39, "2")
                .buildFix();
        assertEquals(soh("11=PARENT-AAPL-2", "39=2"), fix);
        assertTrue(fix.charAt(fix.length() - 1) == SOH);
        assertTrue(nvfix.charAt(nvfix.length() - 1) == SOH);
    }

    @Test
    @DisplayName("enum encode on buildFix / decode on buildNvfix (code or meaning)")
    void enumEncodeDecode() {
        DeltaPublishBuilder fromMeaning = new DeltaPublishBuilder(dictionary)
                .set("OrdStatus", "Filled");
        assertEquals(soh("39=2"), fromMeaning.buildFix());
        assertEquals(soh("OrdStatus=Filled"), fromMeaning.buildNvfix());

        DeltaPublishBuilder fromCode = new DeltaPublishBuilder(dictionary)
                .set(39, "2");
        assertEquals(soh("39=2"), fromCode.buildFix());
        assertEquals(soh("OrdStatus=Filled"), fromCode.buildNvfix());

        DeltaPublishBuilder mixed = new DeltaPublishBuilder(dictionary)
                .set("39", "Filled")
                .set("OrdStatus", "2");
        assertEquals(soh("39=2", "39=2"), mixed.buildFix());
        assertEquals(soh("OrdStatus=Filled", "OrdStatus=Filled"), mixed.buildNvfix());

        assertEquals(
                soh("39=1"),
                new DeltaPublishBuilder(dictionary).set("OrdStatus", "PartiallyFilled").buildFix());
        assertEquals(
                soh("39=1"),
                new DeltaPublishBuilder(dictionary).set("OrdStatus", "Partially filled").buildFix());
    }

    @Test
    @DisplayName("insertion order is preserved for delta publish")
    void insertionOrder() {
        String fix = new DeltaPublishBuilder(dictionary)
                .set(151, "0")
                .set("ClOrdID", "PARENT-AAPL-2")
                .set(39, "Filled")
                .buildFix();
        assertEquals(soh("151=0", "11=PARENT-AAPL-2", "39=2"), fix);

        String nvfix = new DeltaPublishBuilder(dictionary)
                .set("LeavesQty", "0")
                .set(11, "PARENT-AAPL-2")
                .set("OrdStatus", "2")
                .buildNvfix();
        assertEquals(soh("LeavesQty=0", "ClOrdID=PARENT-AAPL-2", "OrdStatus=Filled"), nvfix);
    }

    @Test
    @DisplayName("unknown tags passthrough; no invented required fields")
    void unknownTagsPassthrough() {
        String fix = new DeltaPublishBuilder(dictionary)
                .set(11, "A1")
                .set(9001, "EVT-003")
                .set("9999", "venue-x")
                .set("CustomVenueTag", "keep-me")
                .buildFix();
        assertEquals(soh("11=A1", "9001=EVT-003", "9999=venue-x", "CustomVenueTag=keep-me"), fix);
        assertFalse(fix.contains("35="), "must not invent MsgType");
        assertFalse(fix.contains("8="), "must not invent BeginString");
        assertEquals(4, countFields(fix));

        String nvfix = new DeltaPublishBuilder(dictionary)
                .set(11, "A1")
                .set(9001, "EVT-003")
                .set("CustomVenueTag", "keep-me")
                .buildNvfix();
        assertEquals(soh("ClOrdID=A1", "9001=EVT-003", "CustomVenueTag=keep-me"), nvfix);
        assertFalse(nvfix.contains("MsgType="));

        assertEquals(
                soh("39=Z"),
                new DeltaPublishBuilder(dictionary).set(39, "Z").buildFix());
        assertEquals(
                soh("OrdStatus=Z"),
                new DeltaPublishBuilder(dictionary).set("OrdStatus", "Z").buildNvfix());
    }

    @Test
    @DisplayName("NoAllocs group delta: count then members in AMPS order")
    void noAllocsGroupDelta() {
        String fix = new DeltaPublishBuilder(dictionary)
                .set(78, "1")
                .set("AllocAccount", "ACC-9")
                .set(80, "50")
                .buildFix();
        assertEquals(soh("78=1", "79=ACC-9", "80=50"), fix);

        String nvfix = new DeltaPublishBuilder(dictionary)
                .group("NoAllocs", 1)
                .set(79, "ACC-9")
                .set("AllocShares", "50")
                .end()
                .buildNvfix();
        assertEquals(soh("NoAllocs=1", "AllocAccount=ACC-9", "AllocShares=50"), nvfix);

        String two = new DeltaPublishBuilder(dictionary)
                .group(78, 2)
                .set("AllocAccount", "ACC-1")
                .set(80, "400")
                .end()
                .set(79, "ACC-2")
                .set("AllocShares", "600")
                .buildFix();
        assertEquals(soh("78=2", "79=ACC-1", "80=400", "79=ACC-2", "80=600"), two);
    }

    @Test
    @DisplayName("optional converter is used for buildNvfix")
    void usesProvidedConverter() {
        FixNvfixConverter conv = new FixNvfixConverter(dictionary);
        String nvfix = new DeltaPublishBuilder(dictionary, conv)
                .set(39, "2")
                .buildNvfix();
        assertEquals(soh("OrdStatus=Filled"), nvfix);
        assertEquals(conv, new DeltaPublishBuilder(dictionary, conv).converter());
    }

    @Test
    @DisplayName("get by tag or name after set, either FIX or NVFIX key")
    void getByTagOrNameAfterSet() {
        DeltaPublishBuilder byTag = new DeltaPublishBuilder(dictionary).set(39, "2");
        assertEquals(Optional.of("2"), byTag.get(39));
        assertEquals(Optional.of("2"), byTag.get("39"));
        assertEquals(Optional.of("2"), byTag.get("OrdStatus"));
        assertEquals(Optional.empty(), byTag.get(11));
        assertEquals(Optional.empty(), byTag.get("ClOrdID"));
        assertEquals(Optional.empty(), byTag.get("NoSuchField"));

        DeltaPublishBuilder byName = new DeltaPublishBuilder(dictionary).set("OrdStatus", "Filled");
        assertEquals(Optional.of("Filled"), byName.get("OrdStatus"));
        assertEquals(Optional.of("Filled"), byName.get(39));
        assertEquals(Optional.of("Filled"), byName.get("39"));

        DeltaPublishBuilder mixed = new DeltaPublishBuilder(dictionary)
                .set(11, "PARENT-AAPL-2")
                .set("OrdStatus", "Filled");
        assertEquals(Optional.of("PARENT-AAPL-2"), mixed.get("ClOrdID"));
        assertEquals(Optional.of("PARENT-AAPL-2"), mixed.get(11));
        assertEquals(Optional.of("Filled"), mixed.get(39));
        assertEquals(Optional.of("Filled"), mixed.get("OrdStatus"));
    }

    @Test
    @DisplayName("fromFix / fromNvfix: get works by tag or name on either payload")
    void getOnParsedFixAndNvfix() {
        String fix = soh("11=PARENT-AAPL-2", "39=2", "151=0");
        DeltaPublishBuilder fromFix = DeltaPublishBuilder.fromFix(dictionary, fix);
        assertEquals(Optional.of("PARENT-AAPL-2"), fromFix.get(11));
        assertEquals(Optional.of("PARENT-AAPL-2"), fromFix.get("ClOrdID"));
        assertEquals(Optional.of("2"), fromFix.get(39));
        assertEquals(Optional.of("2"), fromFix.get("OrdStatus"));
        assertEquals(Optional.of("0"), fromFix.get("LeavesQty"));
        assertEquals(Optional.empty(), fromFix.get(55));
        assertEquals(soh("11=PARENT-AAPL-2", "39=2", "151=0"), fromFix.buildFix());
        assertEquals(soh("ClOrdID=PARENT-AAPL-2", "OrdStatus=Filled", "LeavesQty=0"), fromFix.buildNvfix());

        String nvfix = soh("ClOrdID=PARENT-AAPL-2", "OrdStatus=Filled", "LeavesQty=0");
        DeltaPublishBuilder fromNvfix = DeltaPublishBuilder.fromNvfix(dictionary, nvfix);
        assertEquals(Optional.of("PARENT-AAPL-2"), fromNvfix.get(11));
        assertEquals(Optional.of("PARENT-AAPL-2"), fromNvfix.get("ClOrdID"));
        assertEquals(Optional.of("Filled"), fromNvfix.get(39));
        assertEquals(Optional.of("Filled"), fromNvfix.get("OrdStatus"));
        assertEquals(Optional.of("0"), fromNvfix.get(151));
        assertEquals(soh("11=PARENT-AAPL-2", "39=2", "151=0"), fromNvfix.buildFix());
        assertEquals(nvfix, fromNvfix.buildNvfix());
    }

    @Test
    @DisplayName("getAll returns repeating-group occurrences; get is last")
    void getAllRepeatingGroup() {
        DeltaPublishBuilder builder = new DeltaPublishBuilder(dictionary)
                .group(78, 2)
                .set("AllocAccount", "ACC-1")
                .set(80, "400")
                .end()
                .set(79, "ACC-2")
                .set("AllocShares", "600");
        assertEquals(Optional.of("2"), builder.get("NoAllocs"));
        assertEquals(Optional.of("ACC-2"), builder.get(79));
        assertEquals(Optional.of("ACC-2"), builder.get("AllocAccount"));
        assertEquals(List.of("ACC-1", "ACC-2"), builder.getAll(79));
        assertEquals(List.of("ACC-1", "ACC-2"), builder.getAll("AllocAccount"));
        assertEquals(List.of("400", "600"), builder.getAll("AllocShares"));
    }

    @Test
    @DisplayName("get unknown tags by the same numeric or custom name")
    void getUnknownTags() {
        DeltaPublishBuilder builder = new DeltaPublishBuilder(dictionary)
                .set(9001, "EVT-003")
                .set("CustomVenueTag", "keep-me");
        assertEquals(Optional.of("EVT-003"), builder.get(9001));
        assertEquals(Optional.of("EVT-003"), builder.get("9001"));
        assertEquals(Optional.of("keep-me"), builder.get("CustomVenueTag"));
        assertEquals(Optional.empty(), builder.get(9002));
    }

    private static String soh(String... fields) {
        return String.join(String.valueOf(SOH), fields) + SOH;
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
}
