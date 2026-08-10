package com.demo.amps.clients;

import com.crankuptheamps.client.Message;
import com.demo.amps.common.JsonCodec;

/** Rendering AMPS messages so demo output shows what AMPS actually said. */
public final class Messages {

    private Messages() {
    }

    /** Human name for the {@code Message.Command} bit field. */
    public static String commandName(int command) {
        return switch (command) {
            case Message.Command.Publish -> "publish";
            case Message.Command.DeltaPublish -> "delta_publish";
            case Message.Command.SOW -> "sow";
            case Message.Command.Subscribe -> "subscribe";
            case Message.Command.SOWAndSubscribe -> "sow_and_subscribe";
            case Message.Command.DeltaSubscribe -> "delta_subscribe";
            case Message.Command.SOWAndDeltaSubscribe -> "sow_and_delta_subscribe";
            case Message.Command.SOWDelete -> "sow_delete";
            case Message.Command.OOF -> "OOF";
            case Message.Command.GroupBegin -> "group_begin";
            case Message.Command.GroupEnd -> "group_end";
            case Message.Command.Ack -> "ack";
            case Message.Command.Heartbeat -> "heartbeat";
            default -> "command(" + command + ")";
        };
    }

    /** Reason codes matter most on OOF messages: deleted vs expired vs no-longer-matching. */
    public static String reasonName(int reason) {
        return switch (reason) {
            case Message.Reason.None -> "none";
            case Message.Reason.Deleted -> "deleted";
            case Message.Reason.Expired -> "expired";
            case Message.Reason.Match -> "no-longer-matches-filter";
            case Message.Reason.Duplicate -> "duplicate";
            default -> "reason(" + reason + ")";
        };
    }

    /** True for the bracketing messages AMPS sends around a SOW query result. */
    public static boolean isGroupMarker(Message message) {
        int command = message.getCommand();
        return command == Message.Command.GroupBegin || command == Message.Command.GroupEnd;
    }

    /** One-line summary: command, key, and a truncated payload. */
    public static String oneLine(Message message) {
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-24s", commandName(message.getCommand())));
        if (!message.isSowKeyNull()) {
            out.append(" sowkey=").append(String.format("%-22s", message.getSowKey()));
        }
        if (message.getCommand() == Message.Command.OOF && !message.isReasonNull()) {
            out.append(" reason=").append(reasonName(message.getReason()));
        }
        if (!message.isDataNull()) {
            out.append(' ').append(truncate(message.getData(), 90));
        }
        return out.toString();
    }

    /** Payload plus the byte count, which is the number the sizing demos care about. */
    public static String withSize(Message message) {
        String data = message.isDataNull() ? "" : message.getData();
        return String.format("%4d B  %s", JsonCodec.byteSize(data), truncate(data, 100));
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }
}
