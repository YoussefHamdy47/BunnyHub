package org.bunnys.services;

import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;

/** Good deals on paid games (/deals and the daily post). On by default; DEALS_ENABLED=false turns it off. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class GameDeals implements BunnyService {
    @Override public String name() { return "game deals"; }
    @Override public void start(BunnyHub client) { DealSystem.start(client); }
    @Override public void shutdown() { DealSystem.shutdown(); }
}
