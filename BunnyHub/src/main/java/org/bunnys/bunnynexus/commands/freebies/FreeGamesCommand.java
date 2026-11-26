package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
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

import java.util.ArrayList;
import java.util.List;
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
            for (FreebieStore store : FreebieStore.values()) if (!store.news()) launcher.addChoice(store.label(), store.id());
            addOption(launcher);
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = FreebieSystem.current().orElse(null);
            if (system == null) { unavailable(ctx); return; }
            String typed = ctx.getString("launcher");
            Optional<FreebieStore> store = Optional.empty();
            if (typed != null) {
                store = FreebieStore.byId(typed.strip().toLowerCase(Locale.ROOT)).filter(s -> !s.news());
                if (store.isEmpty()) {
                    ctx.replyTransient(SystemEmbeds.error("Unknown launcher", "Try one of: " + String.join(", ",
                            Arrays.stream(FreebieStore.values()).filter(s -> !s.news()).map(FreebieStore::id).toList()) + "."));
                    return;
                }
            }
            ctx.replyMessage(FreebieMessages.liveList(system.liveNow(store), store, system.upcoming()));
        }
    }

    /**
     * Opens the section picker. Picking shows a confirm step ({@code FreebieDmSubscribeSelect}); Confirm
     * ({@code FreebieDmConfirmButtons}) sends the consent DM and saves.
     */
    public static final class Dms extends BunnySubcommand {
        public Dms() {
            setName("dms");
            addAliases("subscribe", "dm");
            setDescription("Get free games by DM for the sections you choose, or turn DMs off.");
            setExample("/free-games dms");
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = FreebieSystem.current().orElse(null);
            if (system == null) { unavailable(ctx); return; }
            var dms = system.dms();
            if (!dms.enabled()) { ctx.replyCard(org.bunnys.handler.cards.Card.fromEmbed(SystemEmbeds.notice("Free-game DMs", "Personal DM alerts are turned off right now.")), true); return; }
            String user = ctx.getUser().getId();
            // Subscribers get the off switch right here, so changing and stopping DMs is one command.
            ctx.replyCard(FreebieDm.pickerCard(user, dms.current(user)), true);
        }
    }

    /**
     * The server's alert dashboard. Discord can only hide a whole command from members, so this branch is visible
     * to everyone and checks Manage Server itself.
     */
    public static final class Setup extends BunnySubcommand {
        public Setup() {
            setName("setup");
            setDescription("Server admins: choose which channels get free-game alerts (needs Manage Server).");
            setExample("/free-games setup");
            setMentionEnabled(false);
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) { FreebieSettingsCommands.openDashboard(ctx); }
    }

    /** How the whole free-game system works, for members and admins alike. */
    public static final class Help extends BunnySubcommand {
        public Help() {
            setName("help");
            setDescription("How free games work here: the list, DMs, server setup and pings.");
            setExample("/free-games help");
        }

        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var autoSend = FreebieSystem.current().map(system -> system.config().autoSend()).orElse(Set.of());
            ctx.replyCard(org.bunnys.handler.cards.Card.fromEmbed(FreebieGuide.embed(autoSend)), true);
        }
    }

    private static void unavailable(CommandContext ctx) {
        ctx.replyCard(org.bunnys.handler.cards.Card.fromEmbed(SystemEmbeds.notice("Free games", "Free-game alerts are not available right now.")), true);
    }
}
