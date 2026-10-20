package com.demo.amps.ha.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crankuptheamps.client.Client;
import org.junit.jupiter.api.Test;

class BookmarkStartTest {

    @Test
    void theWellKnownValuesAreTheClientLibrarysOwn() {
        assertEquals(Client.Bookmarks.EPOCH, BookmarkStart.EPOCH.bookmark());
        assertEquals(Client.Bookmarks.NOW, BookmarkStart.NOW.bookmark());
        assertEquals(Client.Bookmarks.MOST_RECENT, BookmarkStart.MOST_RECENT.bookmark());
        assertTrue(BookmarkStart.EPOCH.isEpoch());
        assertTrue(BookmarkStart.NOW.isNow());
        assertTrue(BookmarkStart.MOST_RECENT.isMostRecent());
        assertFalse(BookmarkStart.MOST_RECENT.isEpoch());
    }

    @Test
    void parsesNamesInAnyCaseAndAnythingElseAsALiteral() {
        assertEquals(BookmarkStart.EPOCH, BookmarkStart.parse("epoch"));
        assertEquals(BookmarkStart.EPOCH, BookmarkStart.parse(" EPOCH "));
        assertEquals(BookmarkStart.NOW, BookmarkStart.parse("Now"));
        assertEquals(BookmarkStart.MOST_RECENT, BookmarkStart.parse("recent"));
        assertEquals(BookmarkStart.MOST_RECENT, BookmarkStart.parse("most_recent"));
        assertEquals(BookmarkStart.MOST_RECENT, BookmarkStart.parse("MOST-RECENT"));
        BookmarkStart literal = BookmarkStart.parse("17434824616529380601|1200|");
        assertEquals("17434824616529380601|1200|", literal.bookmark());
        assertFalse(literal.isEpoch() || literal.isNow() || literal.isMostRecent());
        // The client's own spellings parse to themselves too.
        assertEquals(BookmarkStart.EPOCH, BookmarkStart.parse(Client.Bookmarks.EPOCH));
        assertEquals(BookmarkStart.NOW, BookmarkStart.parse(Client.Bookmarks.NOW));
        assertEquals(BookmarkStart.MOST_RECENT, BookmarkStart.parse(Client.Bookmarks.MOST_RECENT));
    }

    @Test
    void aBlankBookmarkIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BookmarkStart.at(" "));
        assertThrows(IllegalArgumentException.class, () -> BookmarkStart.parse(""));
        assertThrows(NullPointerException.class, () -> BookmarkStart.at(null));
    }

    @Test
    void describesTheWellKnownValuesByNameAndOthersAsTheyAre() {
        assertEquals("EPOCH (the start of the journal)", BookmarkStart.describe(Client.Bookmarks.EPOCH));
        assertEquals("NOW (new messages only)", BookmarkStart.describe(Client.Bookmarks.NOW));
        assertEquals("MOST_RECENT (where the bookmark store says)", BookmarkStart.describe(Client.Bookmarks.MOST_RECENT));
        assertEquals("bookmark '1|5|'", BookmarkStart.describe("1|5|"));
        assertEquals("bookmark '1|5|'", BookmarkStart.at("1|5|").toString());
    }
}
