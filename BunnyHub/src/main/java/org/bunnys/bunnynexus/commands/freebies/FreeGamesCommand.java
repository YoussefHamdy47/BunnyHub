package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieDm;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;
import org.bunnys.utils.SystemEmbeds;

import java.util.Optional;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /free-games list [launcher]}: anyone can see the owner-approved games that are free right now.
 * {@code subscribe} / {@code unsubscribe}: personal DM alerts by section, for anyone, in servers and DMs.
 */
public final class FreeGamesCommand {
    private FreeGamesCommand() {}

    public static final class ListGames extends BunnySubcommand {
        public ListGames() {
            setName("list");
            setDescription("See the games you can claim for free right now.");
            setExample("/free-games list launcher:epic");
            var launcher = new OptionData(OptionType.STRING, "launcher", "Only show one launcher", false);
            for (FreebieStore store : FreebieStore.values()) launcher.addChoice(store.label(), store.id());
            addOption(launcher);
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = FreebieSystem.current().orElse(null);
            if (system == null) { unavailable(ctx); return; }
            String typed = ctx.getString("launcher");
            Optional<FreebieStore> store = Optional.empty();
            if (typed != null) {
                store = FreebieStore.byId(typed.strip().toLowerCase(Locale.ROOT));
                if (store.isEmpty()) {
                    ctx.reply(SystemEmbeds.error("Unknown launcher", "Try one of: " + String.join(", ",
                            Arrays.stream(FreebieStore.values()).map(FreebieStore::id).toList()) + "."), true);
                    return;
                }
            }
            ctx.replyMessage(FreebieMessages.liveList(system.liveNow(store), store));
        }
    }

    /** Opens the section picker; the picker ({@code FreebieDmSubscribeSelect}) does the consent DM and the save. */
    public static final class Subscribe extends BunnySubcommand {
        public Subscribe() {
            setName("subscribe");
            setDescription("Get a DM when a free game is approved, for the sections you choose.");
            setExample("/free-games subscribe");
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = FreebieSystem.current().orElse(null);
            if (system == null) { unavailable(ctx); return; }
            var dms = system.dms();
            if (!dms.enabled()) { ctx.reply(SystemEmbeds.notice("Free-game DMs", "Personal DM alerts are turned off right now."), true); return; }
            String user = ctx.getUser().getId();
            var current = dms.current(user);
            var embed = Embeds.of("📬", current.isPresent() ? "Change your free-game DMs" : "Free-game DMs")
                    .setDescription((current.isPresent() ? "You get DMs for **" + FreebieDm.labels(current.get()) + "**. "
                            : "") + "Pick the sections you want a DM for. At most " + FreebieDm.DAILY_CAP + " a day; "
                            + "every game is checked by the bot owner first.")
                    .setTimestamp(null).build();
            ctx.reply(embed, FreebieDm.picker(user, current.orElse(Set.of())), true);
        }
    }

    public static final class Unsubscribe extends BunnySubcommand {
        public Unsubscribe() {
            setName("unsubscribe");
            setDescription("Stop free-game DMs.");
            setExample("/free-games unsubscribe");
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = FreebieSystem.current().orElse(null);
            if (system == null) { unavailable(ctx); return; }
            boolean removed = system.dms().unsubscribe(ctx.getUser().getId());
            ctx.reply(SystemEmbeds.notice("Free-game DMs", removed ? "Done. You won't get free-game DMs any more."
                    : "You weren't subscribed to free-game DMs."), true);
        }
    }

    private static void unavailable(CommandContext ctx) {
        ctx.reply(SystemEmbeds.notice("Free games", "Free-game alerts are not available right now."), true);
    }
}
