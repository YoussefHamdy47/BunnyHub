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

    @Test void theStatusSaysWhatTheBotDoesAndFitsDiscordsLimit() {
        var lines = ClientReady.statusLines(58);
        assertEquals(3, lines.size());
        assertTrue(lines.contains("58 good Steam and GOG deals right now · /deals"));
        assertTrue(ClientReady.statusLines(0).contains("Big Steam and GOG sales · /deals"), "no count before the first refresh");
        assertTrue(lines.stream().allMatch(l -> l.length() <= 128));
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
