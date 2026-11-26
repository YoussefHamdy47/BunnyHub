package org.bunnys.services;

import org.bunnys.bunnynexus.consoles.ConsoleNewsSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;

/** PS Plus monthly games and Game Pass updates in Console channels. CONSOLE_NEWS_ENABLED=false turns it off. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class ConsoleNewsService implements BunnyService {
    @Override public String name() { return "console news"; }
    @Override public void start(BunnyHub client) { ConsoleNewsSystem.start(client); }
    @Override public void shutdown() { ConsoleNewsSystem.shutdown(); }
}
