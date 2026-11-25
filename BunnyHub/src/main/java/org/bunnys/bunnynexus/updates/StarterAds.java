package org.bunnys.bunnynexus.updates;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The first ads, added once when ads start in a database (owner request 2026-11-25). Editable afterwards with
 * {@code /freebie-admin ad-add} using the same id; deleting them does not bring them back.
 */
final class StarterAds {
    private StarterAds() {}

    /** What the bot's invite asks for: the same as the free-game alerts' own invite line. */
    static final long INVITE_PERMISSIONS = 1024L + 2048L + 16384L + 65536L + 268435456L + 16L;

    static String invite(String applicationId) {
        return "https://discord.com/oauth2/authorize?client_id=" + applicationId + "&permissions=" + INVITE_PERMISSIONS
                + "&scope=bot+applications.commands";
    }

    static List<Ad> all(String applicationId, Instant now) {
        return List.of(
                new Ad("get-bunnynexus", Optional.of("Get BunnyNexus"),
                        "Free games on Epic, Steam, GOG and more, posted the moment they go live. Add BunnyNexus to your own server, "
                                + "or use {/free-games dms} to get them by DM.\nIt also runs study timers, moderation and a live Deals Board.",
                        Optional.empty(), Optional.of(new Ad.Link("Add BunnyNexus", URI.create(invite(applicationId)))),
                        Ad.Audience.ALL, true, now),
                // Newer, so a server without a board sees it first.
                new Ad("deals-board", Optional.of("New: Deals Board"),
                        "One message with the best Steam and GOG sales, kept up to date: 75% off or more on well-reviewed games, "
                                + "cheapest first.\nAdmins can add it in one click: {/free-games setup} → **Create Deals Board**.",
                        Optional.empty(), Optional.empty(), Ad.Audience.NO_DEALS_BOARD, true, now.plusSeconds(1)));
    }
}
