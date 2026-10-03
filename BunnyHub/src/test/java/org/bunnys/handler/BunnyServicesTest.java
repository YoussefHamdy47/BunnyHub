package org.bunnys.handler;

import org.bunnys.services.FreeGameAlerts;
import org.bunnys.services.StudyMenus;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BunnyServicesTest {
    record Probe(String name, List<String> log, boolean failStart) implements BunnyService {
        @Override public void start(BunnyHub client) {
            if (failStart) throw new IllegalStateException("boom");
            log.add("start " + name);
        }
        @Override public void shutdown() {
            log.add("stop " + name);
            throw new IllegalStateException("stop failures must not skip the rest");
        }
    }

    @Test void discoversTheBotsServicesByConvention() {
        var types = BunnyServices.discover(ClassScanner.of("org.bunnys.services"), "org.bunnys.services").stream().map(Object::getClass).toList();
        assertEquals(List.of(FreeGameAlerts.class, StudyMenus.class), types);
    }

    @Test void aFailedStartIsSkippedAndStopsRunInReverseOrder() {
        List<String> log = new ArrayList<>();
        var services = new BunnyServices();
        services.start(null, List.of(new Probe("a", log, false), new Probe("broken", log, true), new Probe("b", log, false)));
        services.stopAll();
        assertEquals(List.of("start a", "start b", "stop b", "stop a"), log);
        services.stopAll();
        assertEquals(4, log.size(), "services are stopped once");
    }

    @Test void basePackageSetsEveryDiscoveryRoot() {
        var config = BunnyHub.create().setBasePackage("org.bunnys").snapshot();
        assertEquals("org.bunnys.commands", config.getCommandPackage());
        assertEquals("org.bunnys.events", config.getEventPackage());
        assertEquals("org.bunnys.buttons", config.getButtonPackage());
        assertEquals("org.bunnys.modals", config.getModalPackage());
        assertEquals("org.bunnys.selects", config.getSelectPackage());
        assertEquals("org.bunnys.services", config.getServicePackage());
    }

    @Test void foldersCanBeRenamedInAnyOrderAndExplicitPackagesWin() {
        var renamed = BunnyHub.create().setFolder(Discovery.COMMANDS, "bunnycmds").setBasePackage("org.bunnys").snapshot();
        assertEquals("org.bunnys.bunnycmds", renamed.getCommandPackage(), "folder set before the base still applies");
        assertEquals("org.bunnys.events", renamed.getEventPackage());

        var explicit = BunnyHub.create().setCommandPackage("com.other.cmds").setBasePackage("org.bunnys")
                .setFolder(Discovery.COMMANDS, "ignored").snapshot();
        assertEquals("com.other.cmds", explicit.getCommandPackage(), "a full package overrides base + folder");

        var nested = BunnyHub.create().setBasePackage("org.bunnys").setFolder(Discovery.SERVICES, "features.lifecycle").snapshot();
        assertEquals("org.bunnys.features.lifecycle", nested.getServicePackage());

        assertNull(BunnyHub.create().snapshot().getCommandPackage(), "nothing is discovered without a base or package");
    }

    @Test void invalidPackageNamesFailFastInsteadOfSilentlyLoadingNothing() {
        assertThrows(IllegalArgumentException.class, () -> BunnyHub.create().setBasePackage("org.bunnys."));
        assertThrows(IllegalArgumentException.class, () -> BunnyHub.create().setFolder(Discovery.COMMANDS, "bunny cmds"));
        assertThrows(IllegalArgumentException.class, () -> BunnyHub.create().setFolder(Discovery.COMMANDS, "1cmds"));
        assertThrows(IllegalArgumentException.class, () -> BunnyHub.create().setBasePackage(null));
    }

    @Test void nothingStartsOnceShutdownHasBegun() {
        List<String> log = new ArrayList<>();
        var services = new BunnyServices();
        services.stopAll(); // e.g. "stop" typed on the console while the bot was still logging in
        services.start(null, List.of(new Probe("late", log, false)));
        assertTrue(log.isEmpty());
    }

    @Test void scannerMatchesWholePackagesOnly() {
        var scanner = ClassScanner.of("org.bunnys");
        assertEquals(2, scanner.find(BunnyService.class, "org.bunnys.services").size());
        assertTrue(scanner.find(BunnyService.class, "org.bunnys.serv").isEmpty(), "a name prefix is not a package");
        assertTrue(scanner.find(BunnyService.class, "org.bunnys.commands").isEmpty(), "wrong kind in a package");
    }
}
