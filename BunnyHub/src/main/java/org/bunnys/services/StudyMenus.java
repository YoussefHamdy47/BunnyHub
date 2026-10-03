package org.bunnys.services;

import org.bunnys.bunnynexus.timers.buttons.GPAPaginator;
import org.bunnys.bunnynexus.timers.services.PendingSessionManager;
import org.bunnys.handler.BunnyService;

/** In-memory study menus (pending sessions and GPA pages) whose timers must stop with the bot. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class StudyMenus implements BunnyService {
    @Override public String name() { return "study menus"; }

    @Override public void shutdown() {
        PendingSessionManager.shutdown();
        GPAPaginator.shutdown();
    }
}
