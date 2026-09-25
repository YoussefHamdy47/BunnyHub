package org.bunnys.bunnynexus.commands.freebies;

import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import java.util.function.BiFunction;

/** /freebie-admin status | pause | resume | check — owner-only (FREEBIE_OWNER_IDS), in addition to developer-only gating. */
public final class FreebieAdminCommands {
    private FreebieAdminCommands() {}

    static final class Action extends BunnySubcommand {
        private final BiFunction<FreebieSystem, String, String> action;
        Action(String name, String description, BiFunction<FreebieSystem, String, String> action) {
            this.action = action; setName(name); setDescription(description);
        }
        @Override public void execute(BunnyHub client, CommandContext ctx) {
            var system = FreebieSystem.current().orElse(null);
            String user = ctx.getUser().getId();
            String text;
            if (system == null) text = "Free-game alerts are not running (check FREEBIE_* in .env and the startup log).";
            else if (!system.config().isOwner(user)) text = "Only freebie owners can use this.";
            else text = action.apply(system, user);
            ctx.reply(new net.dv8tion.jda.api.EmbedBuilder().setColor(org.bunnys.utils.AppDesign.ColorCodes.DEFAULT)
                    .setTitle("Free-game alerts — admin").setDescription(text).build(), true);
        }
    }

    public static BunnySubcommand status() {
        return new Action("status", "Discovery health, review queue and sending progress", FreebieSystem::status);
    }
    public static BunnySubcommand pause() {
        return new Action("pause", "Pause ALL alert sending (discovery and reviews continue)", (s, u) -> s.setPaused(u, true));
    }
    public static BunnySubcommand resume() {
        return new Action("resume", "Resume alert sending", (s, u) -> s.setPaused(u, false));
    }
    public static BunnySubcommand check() {
        return new Action("check", "Check GamerPower for new giveaways right now", FreebieSystem::pollNow);
    }
}
