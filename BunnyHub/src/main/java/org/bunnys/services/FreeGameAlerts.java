package org.bunnys.services;

import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;

/** Free-game discovery and delivery. Stays off, with a logged reason, unless the FREEBIE_* keys are set in .env. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class FreeGameAlerts implements BunnyService {
    @Override public String name() { return "free-game alerts"; }
    @Override public void start(BunnyHub client) { FreebieSystem.start(client); }
    @Override public void shutdown() { FreebieSystem.shutdown(); }
}
