package com.demo.amps.ha.api;

import com.crankuptheamps.client.Client;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a bookmark subscription starts.
 *
 * <ul>
 *   <li>{@link #EPOCH}: everything the transaction log holds. The subscriber
 *       first purges the subscription's state from its bookmark store and,
 *       through the adapter, from the recovery point topic: a store that
 *       remembered the previous position would otherwise filter the first
 *       part of the replay as already seen, and "epoch" would quietly mean
 *       "since the recovery point". Starting over means starting over.</li>
 *   <li>{@link #NOW}: new messages only.</li>
 *   <li>{@link #MOST_RECENT}: resume where the bookmark store says, which
 *       after a restart is what the store recovered from its recovery point
 *       topic -- or the epoch, when there is nothing to resume from.</li>
 *   <li>{@link #at(String)}: a literal bookmark, timestamp or range, exactly
 *       as the AMPS {@code subscribe} command takes it.</li>
 * </ul>
 *
 * @param bookmark the value for the subscribe command's bookmark field
 */
public record BookmarkStart(String bookmark) {

    public static final BookmarkStart EPOCH = new BookmarkStart(Client.Bookmarks.EPOCH);
    public static final BookmarkStart NOW = new BookmarkStart(Client.Bookmarks.NOW);
    public static final BookmarkStart MOST_RECENT = new BookmarkStart(Client.Bookmarks.MOST_RECENT);

    public BookmarkStart {
        Objects.requireNonNull(bookmark, "bookmark");
        if (bookmark.isBlank()) {
            throw new IllegalArgumentException("bookmark must not be blank");
        }
    }

    /** A literal bookmark, timestamp, list or range, as the subscribe command takes it. */
    public static BookmarkStart at(String bookmark) {
        return new BookmarkStart(bookmark);
    }

    /**
     * {@code epoch}, {@code now}, {@code recent} or {@code most_recent} by
     * name (any case), anything else as a literal bookmark.
     */
    public static BookmarkStart parse(String value) {
        Objects.requireNonNull(value, "value");
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "epoch" -> EPOCH;
            case "now" -> NOW;
            case "recent", "most_recent", "most-recent", "mostrecent" -> MOST_RECENT;
            default -> at(value.trim());
        };
    }

    public boolean isEpoch() {
        return Client.Bookmarks.EPOCH.equals(bookmark);
    }

    public boolean isNow() {
        return Client.Bookmarks.NOW.equals(bookmark);
    }

    public boolean isMostRecent() {
        return Client.Bookmarks.MOST_RECENT.equals(bookmark);
    }

    /** A bookmark value in words: the well-known values by name, any other as it is. */
    public static String describe(String bookmark) {
        if (Client.Bookmarks.EPOCH.equals(bookmark)) {
            return "EPOCH (the start of the journal)";
        }
        if (Client.Bookmarks.NOW.equals(bookmark)) {
            return "NOW (new messages only)";
        }
        if (Client.Bookmarks.MOST_RECENT.equals(bookmark)) {
            return "MOST_RECENT (where the bookmark store says)";
        }
        return "bookmark '" + bookmark + "'";
    }

    @Override
    public String toString() {
        return describe(bookmark);
    }
}
