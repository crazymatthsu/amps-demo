package com.demo.amps.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.demo.amps.ha.api.BookmarkStart;
import com.demo.amps.ha.api.RecoveryPointTopic;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HaSettingsTest {

    @Test
    void defaultsPointAtTheComposeStack() {
        HaSettings settings = HaSettings.from(key -> null, key -> null);
        assertEquals(List.of("tcp://127.0.0.1:9007/amps/json", "tcp://127.0.0.1:9107/amps/json"), settings.uris());
        assertEquals("orders", settings.topic());
        assertEquals(3000, settings.count());
        assertEquals(Duration.ofMillis(20), settings.interval());
        assertEquals("ha-demo-publisher", settings.publisherName());
        assertEquals("ha-demo-consumer", settings.consumerName());
        assertEquals("now", settings.bookmark());
        assertEquals(BookmarkStart.NOW, settings.bookmarkStart());
        assertNull(settings.stateDir());
        // The AMPS client library's own default, and what both instance configs define.
        assertEquals("/ADMIN/bookmark_store", settings.recoveryPointTopic());
        assertEquals(HaSettings.DEFAULT_RECOVERY_POINT_TOPIC, settings.recoveryPointTopic());
        assertEquals(RecoveryPointTopic.defaults(), settings.recoveryPoint());
    }

    @Test
    void systemPropertiesWinOverEnvironmentOverDefaults() {
        Map<String, String> properties = Map.of("ha.count", "10", "ha.uris", " tcp://a:1/amps/json , tcp://b:2/amps/json ");
        Map<String, String> env = Map.of("HA_COUNT", "99", "HA_TOPIC", "trades", "HA_INTERVAL_MS", "5",
                "HA_STATE_DIR", "state", "HA_BOOKMARK", "EPOCH", "HA_RUN", "r7",
                "HA_RECOVERY_POINT_TOPIC", "/ops/bookmarks");
        HaSettings settings = HaSettings.from(properties::get, env::get);
        assertEquals(10, settings.count());
        assertEquals(List.of("tcp://a:1/amps/json", "tcp://b:2/amps/json"), settings.uris());
        assertEquals("trades", settings.topic());
        assertEquals(Duration.ofMillis(5), settings.interval());
        assertEquals(Path.of("state"), settings.stateDir());
        assertEquals("epoch", settings.bookmark());
        assertEquals(BookmarkStart.EPOCH, settings.bookmarkStart());
        assertEquals("r7", settings.run());
        assertEquals("/ops/bookmarks", settings.recoveryPointTopic());
        assertEquals(RecoveryPointTopic.of("/ops/bookmarks"), settings.recoveryPoint());
    }

    @Test
    void environmentNamesAreUpperSnakeCase() {
        assertEquals("HA_INTERVAL_MS", HaSettings.envName("intervalMs"));
        assertEquals("HA_STATE_DIR", HaSettings.envName("stateDir"));
        assertEquals("HA_URIS", HaSettings.envName("uris"));
        assertEquals("HA_RECOVERY_POINT_TOPIC", HaSettings.envName("recoveryPointTopic"));
    }

    @Test
    void recentMeansTheBookmarkStoresPosition() {
        HaSettings settings = HaSettings.from(Map.of("ha.bookmark", "recent")::get, key -> null);
        assertEquals(BookmarkStart.MOST_RECENT, settings.bookmarkStart());
    }

    @Test
    void unknownBookmarkIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HaSettings.from(Map.of("ha.bookmark", "yesterday")::get, key -> null));
    }

    @Test
    void noneKeepsBookmarksLocal() {
        HaSettings local = HaSettings.from(Map.of("ha.recoveryPointTopic", "none")::get, key -> null);
        assertNull(local.recoveryPointTopic());
        assertNull(local.recoveryPoint());
        assertNull(HaSettings.from(key -> null, Map.of("HA_RECOVERY_POINT_TOPIC", "NONE")::get).recoveryPointTopic());
    }
}
