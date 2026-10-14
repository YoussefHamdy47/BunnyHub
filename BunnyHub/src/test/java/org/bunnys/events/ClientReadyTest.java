package org.bunnys.events;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** The startup line the owner checks before approving anything on a test instance. */
class ClientReadyTest {
    @Test void productionShowsTheDatabaseAndServerCountOnly() {
        assertEquals("> Database: GBF | Servers: 2", ClientReady.startupScope("GBF", List.of("A", "B"), false));
    }

    @Test void aTestInstanceAlsoNamesTheServersItCanReach() {
        assertEquals("> Database: GBF_test | Servers: 1 | TEST INSTANCE in: Bunny Test",
                ClientReady.startupScope("GBF_test", List.of("Bunny Test"), true));
        var many = IntStream.range(0, 12).mapToObj(i -> "S" + i).toList();
        String line = ClientReady.startupScope("GBF_test", many, true);
        assertTrue(line.startsWith("> Database: GBF_test | Servers: 12 | TEST INSTANCE in: S0,"));
        assertTrue(line.endsWith("S9, ..."), line);
    }
}
