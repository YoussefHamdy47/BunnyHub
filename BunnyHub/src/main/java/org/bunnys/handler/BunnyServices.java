package org.bunnys.handler;

import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts and stops {@link BunnyService}s. Stops run in reverse start order and never skip each other. Once
 * {@link #stopAll()} has run, nothing starts any more, so a shutdown that lands while the bot is still logging in
 * cannot be followed by services starting against a closed database.
 */
final class BunnyServices {
    private final List<BunnyService> started = new ArrayList<>();
    private boolean closed;

    /** Every concrete service in {@code packageName}, in class-name order so startup is deterministic. */
    static List<BunnyService> discover(ClassScanner scanner, String packageName) {
        List<BunnyService> services = new ArrayList<>();
        for (var type : scanner.find(BunnyService.class, packageName)) {
            try {
                services.add(type.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot create service " + type.getName() + " (needs a public no-argument constructor)", error);
            }
        }
        return services;
    }

    /** A service that fails to start is logged and skipped; the bot keeps running without it. */
    synchronized void start(BunnyHub client, List<BunnyService> services) {
        for (BunnyService service : services) {
            if (closed) return;
            try {
                service.start(client);
                started.add(service);
            } catch (RuntimeException failure) {
                BunnyLog.error("[Services] " + service.name() + " failed to start | " + FailureDiagnostics.describe(failure));
            }
        }
        if (!started.isEmpty()) BunnyLog.success("[Services] Started " + started.size() + " service(s).");
    }

    synchronized void stopAll() {
        closed = true;
        for (int i = started.size() - 1; i >= 0; i--) {
            BunnyService service = started.get(i);
            new ShutdownAction(service.name(), service::shutdown).run();
        }
        started.clear();
    }
}
