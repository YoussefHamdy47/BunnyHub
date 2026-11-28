package org.bunnys.bunnynexus.updates;

import org.bunnys.utils.AppDesign.Emojis;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The bot's own ads, kept in the database by version ({@link AdStore#seed}): a new ad is added once, a higher version
 * replaces the saved one, and an ad the owner deleted stays deleted until its version goes up. Editable with
 * {@code /freebie-admin ad-add} using the same id.
 *
 * <p>The plan (owner request 2026-11-28: ads that say what is happening now, at the right moment, with the bot's own
 * emojis). The ad sits under a free-game alert, the moment people are happiest with the bot, so:
 * <ol>
 *   <li><b>A real deal</b> (servers without a Deals Board): "RDR2 is 75% off until Friday" sells the board better
 *       than describing it.</li>
 *   <li><b>A free game this channel does not get</b>: proof of what they are missing, with the fix (DMs or setup).</li>
 *   <li><b>After a big game</b> ($20+): the peak moment to ask people to add the bot to their own server.</li>
 *   <li><b>The monthly total</b>: "$312 in free games this month", the honest case for a vote that keeps it free.</li>
 *   <li>Evergreen ads only when nothing live applies.</li>
 * </ol>
 */
final class StarterAds {
    private StarterAds() {}

    /** What the bot's invite asks for: the same as the free-game alerts' own invite line. */
    static final long INVITE_PERMISSIONS = 1024L + 2048L + 16384L + 65536L + 268435456L + 16L;
    static final String TOPGG_BOT = "https://top.gg/bot/795361755223556116";

    static String invite(String applicationId) {
        return "https://discord.com/oauth2/authorize?client_id=" + applicationId + "&permissions=" + INVITE_PERMISSIONS
                + "&scope=bot+applications.commands";
    }

    /** Every launcher the bot covers, as the bot's own emojis: a one-line picture of what it does. */
    static final String LAUNCHERS = String.join(" ", Emojis.EPIC, Emojis.STEAM, Emojis.GOG, Emojis.PLAYSTATION, Emojis.XBOX,
            Emojis.SWITCH, Emojis.ANDROID, Emojis.IOS);

    private static Ad.Link link(String emoji, String label, String url) { return new Ad.Link(label, url, Optional.ofNullable(emoji)); }

    private static Ad ad(String id, String emoji, String title, String text, List<Ad.Link> links, Ad.Audience audience,
                         Ad.When when, int priority, Instant created) {
        return new Ad(id, Optional.of(title), text, Optional.empty(), links, audience, true, created, Optional.of(emoji), when, priority);
    }

    /** Each ad with its version. Higher priority wins when several fit; live ads only show while their data exists. */
    static Map<Ad, Integer> all(String applicationId, Instant now) {
        String invite = invite(applicationId);
        Map<Ad, Integer> ads = new LinkedHashMap<>();
        // 1. A real deal, for servers without a Deals Board.
        ads.put(ad("deal-spotlight", "{deal.emoji}", "{deal.name} Is {deal.off} Off",
                "**{deal.price}** ~~{deal.was}~~ on {deal.store}, ends {deal.ends}.\n"
                        + "The Deals Board posts sales like this here by itself: 75% off or more on games players love, from Steam, GOG, "
                        + "PlayStation, Xbox and Switch.\n-# Admins: {/free-games setup} → **Create Deals Board** · Everyone: {/deals}",
                List.of(link("{deal.emoji}", "Open on {deal.store}", "{deal.url}"), link(Emojis.VERIFY, "Add BunnyNexus", "{invite}")),
                Ad.Audience.NO_DEALS_BOARD, Ad.When.ANY, 30, now), 1);
        // 2. A free game this channel does not get.
        ads.put(ad("also-free", "{free.emoji}", "Also Free Right Now: {free.name}",
                "{free} is free to keep on **{free.store}** until {free.ends}, but this channel doesn't post {free.store} games.\n"
                        + "Get them by DM with {/free-games dms}, or ask an admin to add them in {/free-games setup}.",
                List.of(link("{free.emoji}", "Claim on {free.store}", "{free.url}")),
                Ad.Audience.ALL, Ad.When.ANY, 25, now.plusSeconds(1)), 1);
        // 3. Right after a big free game: share it.
        ads.put(ad("big-game", Emojis.VERIFY, "{game.worth} Game, Free Today",
                "That's {game.worth} you didn't spend. Know a server that would want this?\n"
                        + "BunnyNexus posts every free game the moment it goes live, and sets up in one click.\n" + LAUNCHERS,
                List.of(link(Emojis.VERIFY, "Add BunnyNexus", "{invite}"), link(null, "Join Our Server", "{server}")),
                Ad.Audience.ALL, Ad.When.BIG_GAME, 20, now.plusSeconds(2)), 1);
        // 4. The month's total: the honest case for a vote.
        ads.put(ad("month-value", Emojis.VERIFY, "{month.worth} in Free Games This Month",
                "BunnyNexus posted **{month.count} free games worth {month.worth}** in the last 30 days, and it stays free for everyone. "
                        + "A [vote on Top.gg]({vote}) takes ten seconds and helps more people find it.\n-# You can vote every 12 hours.",
                List.of(link(Emojis.VERIFY, "Vote on Top.gg", "{vote}"), link(null, "Join Our Server", "{server}")),
                Ad.Audience.ALL, Ad.When.ANY, 15, now.plusSeconds(3)), 1);
        // Evergreen, when nothing live applies.
        ads.put(ad("get-bunnynexus", Emojis.VERIFY, "Get Free Games in Your Server",
                LAUNCHERS + "\nFree-to-keep games from every launcher, posted the moment they go live, with pings only for what "
                        + "members pick. One click: {/free-games setup} → **Set Up Everything**. Rather get them privately? {/free-games dms}",
                List.of(link(Emojis.VERIFY, "Add to Your Server", invite), link(null, "See Reviews", TOPGG_BOT)),
                Ad.Audience.ALL, Ad.When.ANY, 2, now.plusSeconds(4)), 2);
        ads.put(ad("deals-board", Emojis.STEAM, "Big Game Deals, Posted for You",
                "75% off or more on well-rated games from **Steam, GOG, PlayStation, Xbox and Nintendo Switch**, posted when new "
                        + "deals arrive.\n-# Admins: {/free-games setup} → **Create Deals Board** · Everyone: {/deals}",
                List.of(link(Emojis.VERIFY, "Add BunnyNexus", invite)),
                Ad.Audience.NO_DEALS_BOARD, Ad.When.ANY, 1, now.plusSeconds(5)), 2);
        ads.put(ad("join-community", Emojis.VERIFY, "Join the BunnyNexus Community",
                "Get help with setup, suggest what we build next, and talk games with people who never pay full price.",
                List.of(link(null, "Join Our Server", "{server}"), link(null, "Vote on Top.gg", "{vote}")),
                Ad.Audience.ALL, Ad.When.ANY, 0, now.plusSeconds(6)), 1);
        return ads;
    }
}
