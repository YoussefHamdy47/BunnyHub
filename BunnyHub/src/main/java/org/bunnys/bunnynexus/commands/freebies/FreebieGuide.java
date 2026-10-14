package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.utils.Embeds;

import java.util.Set;

/** {@code /free-games help} and the dashboard's Help button: everything the free-game system does, in one page. */
public final class FreebieGuide {
    private FreebieGuide() {}

    /** {@code autoSend}: launchers posted without owner review, so the guide says what really happens. */
    public static MessageEmbed embed(Set<FreebieStore> autoSend) {
        String checked = autoSend.isEmpty()
                ? "Every game is checked by the bot owner before it is posted, so you only get real, free games."
                : "**" + labels(autoSend) + "** games are posted as soon as they are found. "
                        + "Games from other launchers are checked by the bot owner first.";
        return Embeds.of(null, "Free games guide")
                .setDescription("Get free games from Epic, Steam, GOG, consoles, mobile and more.")
                .addField("For everyone",
                        "`/free-games list` See what's free right now. Add `launcher:` to show just one.\n"
                                + "`/free-games dms` Get free games by DM for the sections you pick, or stop them.", false)
                .addField("Set up your server (needs Manage Server)",
                        "1. Run `/free-games setup`.\n"
                                + "2. Click **Quick setup**, pick a channel and what to post. That's it.\n"
                                + "Prefer separate channels? **Create channels for me** makes them, with a ping role each.", false)
                .addField("Make it yours",
                        "**Sections** (Major PC, Indie, Console, Mobile): click one to change its channel, launchers, "
                                + "ping or ending-soon reminder. Untick every launcher to turn it off.\n"
                                + "**Launcher pings**: a different role per launcher, like @Epic for Epic only and @Steam for Steam only.\n"
                                + "**Post role picker**: posts buttons so members can opt in to the ping roles themselves.", false)
                .addField("How games are checked", checked, false)
                .setTimestamp(null).build();
    }

    private static String labels(Set<FreebieStore> stores) {
        return String.join(", ", stores.stream().sorted().map(FreebieStore::label).toList());
    }
}
