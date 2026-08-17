package com.demo.amps.common.fix;

import com.crankuptheamps.client.FIXBuilder;
import com.crankuptheamps.client.FIXShredder;
import com.crankuptheamps.client.NVFIXBuilder;
import com.crankuptheamps.client.NVFIXShredder;
import com.crankuptheamps.client.exception.CommandException;
import com.demo.amps.market.v1.OrderEvent;
import com.demo.amps.market.v1.Side;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encoding {@link OrderEvent}s onto AMPS's two native FIX wire formats, using
 * the official client helpers ({@code FIXBuilder}/{@code NVFIXBuilder} to
 * build, {@code FIXShredder}/{@code NVFIXShredder} to parse).
 *
 * <p>The two formats differ only in how a field is named:
 *
 * <pre>
 *   fix    35=8\x01 11=A1\x01 39=1\x01 14=200\x01 ...     (tag numbers)
 *   nvfix  MsgType=8\x01 ClOrdID=A1\x01 OrdStatus=1\x01 ...  (tag names)
 * </pre>
 *
 * both SOH (0x01) separated, both parsed natively by AMPS -- which is the whole
 * point: SOW keys ({@code /37} or {@code /OrderID}) and content filters
 * ({@code /39 = '2'} or {@code /OrdStatus = '2'}) work against the raw payload
 * with no JSON translation step.
 *
 * <p>Only application-level tags are carried. Session-level FIX tags --
 * BeginString (8), BodyLength (9), CheckSum (10), MsgSeqNum (34), the comp IDs
 * (49/56) -- belong to a FIX <i>session</i> between two engines. AMPS is not a
 * FIX session endpoint; publishers hand it application payloads, and sequencing
 * and durability come from the transaction log and bookmarks instead.
 */
public final class FixWire {

    /** Standard FIX field separator. */
    public static final byte SOH = 0x01;

    /**
     * Event id travels as a custom tag ({@code 9001}, in the user-defined
     * range) so the events SOW can key every message uniquely -- the same job
     * {@code /eventId} does on the JSON topics.
     */
    public static final int TAG_EVENT_ID = 9001;
    public static final String NAME_EVENT_ID = "EventId";

    private FixWire() {
    }

    // ---- encoding ----------------------------------------------------------

    /** OrderEvent as a raw FIX payload: {@code 35=8\x0111=A1\x01...}. */
    public static String toFix(OrderEvent event) {
        try {
            FIXBuilder builder = new FIXBuilder(512, SOH);
            for (Map.Entry<Integer, String> field : fields(event).entrySet()) {
                builder.append(field.getKey(), field.getValue());
            }
            return new String(builder.getBytes(), 0, builder.getSize(), StandardCharsets.UTF_8);
        } catch (CommandException e) {
            throw new IllegalArgumentException("cannot encode event " + event.getEventId(), e);
        }
    }

    /** OrderEvent as an NVFIX payload: {@code MsgType=8\x01ClOrdID=A1\x01...}. */
    public static String toNvfix(OrderEvent event) {
        try {
            NVFIXBuilder builder = new NVFIXBuilder(512, SOH);
            for (Map.Entry<Integer, String> field : fields(event).entrySet()) {
                builder.append(nameOf(field.getKey()), field.getValue());
            }
            return new String(builder.getBytes(), 0, builder.getSize(), StandardCharsets.UTF_8);
        } catch (CommandException e) {
            throw new IllegalArgumentException("cannot encode event " + event.getEventId(), e);
        }
    }

    // ---- decoding ----------------------------------------------------------

    /** Raw FIX payload back to tag-number -> value. */
    public static Map<Integer, String> parseFix(String payload) {
        Map<Integer, String> out = new LinkedHashMap<>();
        new FIXShredder(SOH).toMap(payload)
                .forEach((tag, value) -> out.put(tag, value.toString()));
        return out;
    }

    /** NVFIX payload back to name -> value. */
    public static Map<String, String> parseNvfix(String payload) {
        Map<String, String> out = new LinkedHashMap<>();
        new NVFIXShredder(SOH).toNVMap(payload)
                .forEach((name, value) -> out.put(name.toString(), value.toString()));
        return out;
    }

    /** Rebuilds an OrderEvent from a parsed FIX tag map (for display and tests). */
    public static OrderEvent fromFixMap(Map<Integer, String> tags) {
        OrderEvent.Builder event = OrderEvent.newBuilder()
                .setMsgType(tags.getOrDefault(35, ""))
                .setEventId(tags.getOrDefault(TAG_EVENT_ID, ""))
                .setClOrdId(tags.getOrDefault(11, ""))
                .setOrigClOrdId(tags.getOrDefault(41, ""))
                .setOrderId(tags.getOrDefault(37, ""))
                .setExecId(tags.getOrDefault(17, ""))
                .setSymbol(tags.getOrDefault(55, ""))
                .setOrdStatus(tags.getOrDefault(39, ""))
                .setExecType(tags.getOrDefault(150, ""))
                .setExecTransType(tags.getOrDefault(20, ""))
                .setCxlRejResponseTo(tags.getOrDefault(434, ""))
                .setText(tags.getOrDefault(58, ""));
        event.setSide(switch (tags.getOrDefault(54, "")) {
            case "1" -> Side.SIDE_BUY;
            case "2" -> Side.SIDE_SELL;
            default -> Side.SIDE_UNSPECIFIED;
        });
        tagInt(tags, 38, event::setOrderQty);
        tagInt(tags, 14, event::setCumQty);
        tagInt(tags, 151, event::setLeavesQty);
        tagInt(tags, 32, event::setLastShares);
        tagDouble(tags, 44, event::setPrice);
        tagDouble(tags, 6, event::setAvgPx);
        tagDouble(tags, 31, event::setLastPx);
        return event.build();
    }

    // ---- the field table ---------------------------------------------------

    /**
     * The application tags an event carries, in a stable order. FIX values are
     * all text on the wire; numbers are rendered as plain decimals (never
     * scientific notation, which FIX does not allow).
     */
    private static Map<Integer, String> fields(OrderEvent event) {
        Map<Integer, String> fields = new LinkedHashMap<>();
        fields.put(35, event.getMsgType());
        fields.put(TAG_EVENT_ID, event.getEventId());
        put(fields, 11, event.getClOrdId());
        put(fields, 41, event.getOrigClOrdId());
        put(fields, 37, event.getOrderId());
        put(fields, 17, event.getExecId());
        put(fields, 55, event.getSymbol());
        if (event.getSide() == Side.SIDE_BUY) {
            fields.put(54, "1");
        } else if (event.getSide() == Side.SIDE_SELL) {
            fields.put(54, "2");
        }
        put(fields, 39, event.getOrdStatus());
        put(fields, 150, event.getExecType());
        put(fields, 20, event.getExecTransType());
        if (event.getOrderQty() > 0) {
            fields.put(38, Integer.toString(event.getOrderQty()));
        }
        if (event.getPrice() > 0) {
            fields.put(44, decimal(event.getPrice()));
        }
        // Execution reports always carry the cumulative trio, zeros included:
        // 14=0 on an ack is information, not an omitted field.
        if ("8".equals(event.getMsgType())) {
            fields.put(14, Integer.toString(event.getCumQty()));
            fields.put(151, Integer.toString(event.getLeavesQty()));
            fields.put(6, decimal(event.getAvgPx()));
            if (event.getLastShares() > 0) {
                fields.put(32, Integer.toString(event.getLastShares()));
                fields.put(31, decimal(event.getLastPx()));
            }
        }
        put(fields, 434, event.getCxlRejResponseTo());
        put(fields, 58, event.getText());
        return fields;
    }

    /** Tag number to NVFIX name; one table so the two encodings cannot drift. */
    public static String nameOf(int tag) {
        return switch (tag) {
            case 35 -> "MsgType";
            case TAG_EVENT_ID -> NAME_EVENT_ID;
            case 11 -> "ClOrdID";
            case 41 -> "OrigClOrdID";
            case 37 -> "OrderID";
            case 17 -> "ExecID";
            case 55 -> "Symbol";
            case 54 -> "Side";
            case 39 -> "OrdStatus";
            case 150 -> "ExecType";
            case 20 -> "ExecTransType";
            case 38 -> "OrderQty";
            case 44 -> "Price";
            case 14 -> "CumQty";
            case 151 -> "LeavesQty";
            case 6 -> "AvgPx";
            case 32 -> "LastShares";
            case 31 -> "LastPx";
            case 434 -> "CxlRejResponseTo";
            case 58 -> "Text";
            default -> Integer.toString(tag);
        };
    }

    /** Renders a payload with visible separators, for console output. */
    public static String printable(String payload) {
        return payload.replace((char) SOH, '|');
    }

    private static void put(Map<Integer, String> fields, int tag, String value) {
        if (value != null && !value.isEmpty()) {
            fields.put(tag, value);
        }
    }

    private static String decimal(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void tagInt(Map<Integer, String> tags, int tag, java.util.function.IntConsumer setter) {
        String value = tags.get(tag);
        if (value != null && !value.isEmpty()) {
            setter.accept(Integer.parseInt(value));
        }
    }

    private static void tagDouble(Map<Integer, String> tags, int tag,
                                  java.util.function.DoubleConsumer setter) {
        String value = tags.get(tag);
        if (value != null && !value.isEmpty()) {
            setter.accept(Double.parseDouble(value));
        }
    }
}
