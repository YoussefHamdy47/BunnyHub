package org.bunnys.services;

import org.bunnys.bunnynexus.ratings.StarEmojis;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;

/** The star emojis used for game ratings: uploaded once as the bot's own emojis, then only looked up. */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class RatingStars implements BunnyService {
    @Override public String name() { return "rating stars"; }
    @Override public void start(BunnyHub client) { StarEmojis.ensure(client.getJDA()); }
    @Override public void shutdown() {}
}
