package org.bunnys;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The database override keeps production on GBF by default, and a test instance can never run on it. */
class DeploymentTest {
    static String name(String value) {
        Map<String, String> env = new HashMap<>();
        if (value != null) env.put(Deployment.DATABASE_KEY, value);
        return Deployment.databaseName(env::get);
    }

    @Test void withoutAnOverrideTheDatabaseStaysGbf() {
        assertEquals("GBF", Deployment.PRODUCTION_DATABASE);
        assertEquals("GBF", name(null));
        assertEquals("GBF", name(""));
        assertEquals("GBF", name("   "));
    }

    @Test void anOverrideIsUsedAsGiven() {
        assertEquals("GBF_test", name("GBF_test"));
        assertEquals("bunny-live-test", name("  bunny-live-test "));
    }

    @Test void invalidNamesAreRefusedRatherThanGuessed() {
        for (String bad : new String[]{"a.b", "a/b", "a b", "$db", "x".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> name(bad), bad);
    }

    @Test void aTestInstanceRefusesTheProductionDatabase() {
        assertThrows(IllegalStateException.class, () -> Deployment.requireSafe(true, "GBF"));
        assertThrows(IllegalStateException.class, () -> Deployment.requireSafe(true, "gbf"), "case-insensitive, as on Windows");
        assertDoesNotThrow(() -> Deployment.requireSafe(true, "GBF_test"));
        assertDoesNotThrow(() -> Deployment.requireSafe(false, "GBF"), "production itself is unchanged");
    }

    @Test void aTestInstanceIsOnlyThatWhenAskedFor() {
        assertFalse(Deployment.testInstance(), "the normal start script sets no test flag");
    }
}
