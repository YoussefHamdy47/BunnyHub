package org.bunnys.services;

import org.bunnys.bunnynexus.updates.ProductUpdates;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;

/** Owner-sent product updates to registered servers (docs/product-updates). Needs the database. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class ProductUpdateService implements BunnyService {
    @Override public String name() { return "product updates"; }
    @Override public void start(BunnyHub client) { ProductUpdates.start(client); }
    @Override public void shutdown() { ProductUpdates.shutdown(); }
}
