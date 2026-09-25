package org.bunnys.handler.database;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MongoSettingsTest {
    @Test void databaseWaitsHaveFiniteBudgetsEvenIfUriRequestsUnlimitedWaits() {
        var settings = MongoManager.settings("mongodb://localhost/?socketTimeoutMS=0&connectTimeoutMS=0", 36, Duration.ofSeconds(10));
        assertEquals(10000L, settings.getTimeout(TimeUnit.MILLISECONDS));
        assertEquals(36, settings.getConnectionPoolSettings().getMaxSize());
        assertEquals(2000, settings.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS));
        assertEquals(5000, settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS));
        assertEquals(5000, settings.getSocketSettings().getConnectTimeout(TimeUnit.MILLISECONDS));
        assertEquals(10000, settings.getSocketSettings().getReadTimeout(TimeUnit.MILLISECONDS));
    }

    @Test void shortBudgetsApplyToAllWaitsAndInvalidConfigurationFails() {
        var settings = MongoManager.settings("mongodb://localhost", 4, Duration.ofMillis(500));
        assertEquals(500, settings.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.MILLISECONDS));
        assertEquals(500, settings.getClusterSettings().getServerSelectionTimeout(TimeUnit.MILLISECONDS));
        assertThrows(IllegalArgumentException.class, () -> MongoManager.settings("mongodb://localhost", 0, Duration.ofSeconds(10)));
        assertThrows(IllegalArgumentException.class, () -> MongoManager.settings("mongodb://localhost", 4, Duration.ZERO));
    }
}
