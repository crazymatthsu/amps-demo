package com.demo.amps.cli.fix;

import com.demo.amps.common.fix.FixWire;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FIX 4.2 field dictionary used to expand raw {@code tag=value} (or already-named
 * NVFIX) into tag names and enumerated meanings.
 *
 * <p>The table is the subset of FIX 4.2 that this repo actually puts on the wire
 * (order flow: 35=D/G/F/8/9 plus the usual header/application tags), plus the
 * custom event-id tag {@link FixWire#TAG_EVENT_ID} the native demos use. Unknown
 * tags and free-text values pass through unchanged.
 */
public final class Fix42Dictionary {

    private static final Map<Integer, String> NAMES = new HashMap<>();
    private static final Map<String, Integer> TAGS_BY_NAME = new HashMap<>();
    private static final Map<Integer, Map<String, String>> ENUMS = new HashMap<>();

    static {
        name(8, "BeginString");
        name(9, "BodyLength");
        name(10, "CheckSum");
        name(1, "Account");
        name(6, "AvgPx");
        name(11, "ClOrdID");
        name(14, "CumQty");
        name(15, "Currency");
        name(17, "ExecID");
        name(18, "ExecInst");
        name(20, "ExecTransType");
        name(21, "HandlInst");
        name(22, "IDSource");
        name(31, "LastPx");
        name(32, "LastShares");
        name(34, "MsgSeqNum");
        name(35, "MsgType");
        name(37, "OrderID");
        name(38, "OrderQty");
        name(39, "OrdStatus");
        name(40, "OrdType");
        name(41, "OrigClOrdID");
        name(44, "Price");
        name(48, "SecurityID");
        name(49, "SenderCompID");
        name(52, "SendingTime");
        name(54, "Side");
        name(55, "Symbol");
        name(56, "TargetCompID");
        name(58, "Text");
        name(59, "TimeInForce");
        name(60, "TransactTime");
        name(63, "SettlmntTyp");
        name(64, "FutSettDate");
        name(76, "ExecBroker");
        name(77, "OpenClose");
        name(99, "StopPx");
        name(110, "MinQty");
        name(111, "MaxFloor");
        name(114, "LocateReqd");
        name(126, "ExpireTime");
        name(150, "ExecType");
        name(151, "LeavesQty");
        name(167, "SecurityType");
        name(200, "MaturityMonthYear");
        name(201, "PutOrCall");
        name(202, "StrikePrice");
        name(207, "SecurityExchange");
        name(434, "CxlRejResponseTo");
        name(439, "ClearingFirm");
        name(440, "ClearingAccount");
        name(FixWire.TAG_EVENT_ID, FixWire.NAME_EVENT_ID);

        enums(35, Map.ofEntries(
                entry("0", "Heartbeat"),
                entry("1", "TestRequest"),
                entry("2", "ResendRequest"),
                entry("3", "Reject"),
                entry("4", "SequenceReset"),
                entry("5", "Logout"),
                entry("8", "ExecutionReport"),
                entry("9", "OrderCancelReject"),
                entry("A", "Logon"),
                entry("D", "NewOrderSingle"),
                entry("E", "NewOrderList"),
                entry("F", "OrderCancelRequest"),
                entry("G", "OrderCancelReplaceRequest"),
                entry("H", "OrderStatusRequest"),
                entry("J", "Allocation"),
                entry("K", "ListCancelRequest"),
                entry("L", "ListExecute"),
                entry("M", "ListStatusRequest"),
                entry("N", "ListStatus"),
                entry("P", "AllocationACK"),
                entry("Q", "DontKnowTrade"),
                entry("R", "QuoteRequest"),
                entry("S", "Quote"),
                entry("T", "SettlementInstructions")));

        enums(54, Map.of(
                "1", "Buy",
                "2", "Sell",
                "3", "BuyMinus",
                "4", "SellPlus",
                "5", "SellShort",
                "6", "SellShortExempt",
                "7", "Undisclosed",
                "8", "Cross",
                "9", "CrossShort"));

        enums(39, Map.ofEntries(
                entry("0", "New"),
                entry("1", "PartiallyFilled"),
                entry("2", "Filled"),
                entry("3", "DoneForDay"),
                entry("4", "Canceled"),
                entry("5", "Replaced"),
                entry("6", "PendingCancel"),
                entry("7", "Stopped"),
                entry("8", "Rejected"),
                entry("9", "Suspended"),
                entry("A", "PendingNew"),
                entry("B", "Calculated"),
                entry("C", "Expired")));

        // FIX 4.2 ExecType (150) matches OrdStatus for 0-C, then D/E.
        enums(150, Map.ofEntries(
                entry("0", "New"),
                entry("1", "PartialFill"),
                entry("2", "Fill"),
                entry("3", "DoneForDay"),
                entry("4", "Canceled"),
                entry("5", "Replace"),
                entry("6", "PendingCancel"),
                entry("7", "Stopped"),
                entry("8", "Rejected"),
                entry("9", "Suspended"),
                entry("A", "PendingNew"),
                entry("B", "Calculated"),
                entry("C", "Expired"),
                entry("D", "Restated"),
                entry("E", "PendingReplace")));

        enums(20, Map.of(
                "0", "New",
                "1", "Cancel",
                "2", "Correct",
                "3", "Status"));

        enums(40, Map.ofEntries(
                entry("1", "Market"),
                entry("2", "Limit"),
                entry("3", "Stop"),
                entry("4", "StopLimit"),
                entry("5", "MarketOnClose"),
                entry("6", "WithOrWithout"),
                entry("7", "LimitOrBetter"),
                entry("8", "LimitWithOrWithout"),
                entry("9", "OnBasis"),
                entry("P", "Pegged")));

        enums(59, Map.of(
                "0", "Day",
                "1", "GoodTillCancel",
                "2", "AtTheOpening",
                "3", "ImmediateOrCancel",
                "4", "FillOrKill",
                "5", "GoodTillCrossing",
                "6", "GoodTillDate"));

        enums(21, Map.of(
                "1", "AutomatedPrivateNoBroker",
                "2", "AutomatedPublicBrokerOk",
                "3", "ManualBestExecution"));

        enums(434, Map.of(
                "1", "OrderCancelRequest",
                "2", "OrderCancelReplaceRequest"));

        enums(63, Map.of(
                "0", "Regular",
                "1", "Cash",
                "2", "NextDay",
                "3", "TPlus2",
                "4", "TPlus3",
                "5", "TPlus4",
                "6", "Future",
                "7", "WhenAndIfIssued",
                "8", "SellersOption",
                "9", "TPlus5"));

        enums(201, Map.of(
                "0", "Put",
                "1", "Call"));

        enums(77, Map.of(
                "O", "Open",
                "C", "Close"));
    }

    private Fix42Dictionary() {
    }

    /** FIX 4.2 tag name, or the existing {@link FixWire#nameOf(int)} fallback. */
    public static String nameOf(int tag) {
        String name = NAMES.get(tag);
        if (name != null) {
            return name;
        }
        return FixWire.nameOf(tag);
    }

    /** Tag number for an NVFIX field name, or empty if unknown. */
    public static int tagOf(String name) {
        if (name == null || name.isBlank()) {
            return -1;
        }
        Integer tag = TAGS_BY_NAME.get(name);
        if (tag != null) {
            return tag;
        }
        // Case-insensitive lookup so OrdStatus / ordstatus both work.
        Integer folded = TAGS_BY_NAME.get(name.toLowerCase(Locale.ROOT));
        return folded == null ? -1 : folded;
    }

    /**
     * Enumerated meaning for a FIX 4.2 value, or {@code value} itself when the
     * field is free-text or the code is not in the spec table.
     */
    public static String meaningOf(int tag, String value) {
        if (value == null) {
            return "";
        }
        Map<String, String> table = ENUMS.get(tag);
        if (table == null) {
            return value;
        }
        String meaning = table.get(value);
        return meaning == null ? value : meaning;
    }

    public static Map<Integer, String> names() {
        return Collections.unmodifiableMap(NAMES);
    }

    private static void name(int tag, String name) {
        NAMES.put(tag, name);
        TAGS_BY_NAME.put(name, tag);
        TAGS_BY_NAME.put(name.toLowerCase(Locale.ROOT), tag);
    }

    private static void enums(int tag, Map<String, String> values) {
        ENUMS.put(tag, Map.copyOf(values));
    }

    private static Map.Entry<String, String> entry(String code, String meaning) {
        return Map.entry(code, meaning);
    }
}
