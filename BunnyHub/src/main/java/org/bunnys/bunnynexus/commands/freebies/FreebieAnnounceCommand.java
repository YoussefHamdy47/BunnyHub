package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieBroadcasts;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;

/**
 * /freebie-admin announce: an owner message to every server with free-game alerts. Only previews; sending needs the
 * confirmation button, which works only in a configured review channel. Type a backslash and n for a line break.
 */
public final class FreebieAnnounceCommand extends BunnySubcommand {
    public FreebieAnnounceCommand() {
        setName("announce");
        setDescription("Preview an announcement to every server with free-game alerts (sending needs confirmation)");
        addOption(new OptionData(OptionType.STRING, "title", "Announcement title", true).setMaxLength(FreebieBroadcasts.MAX_TITLE));
        addOption(new OptionData(OptionType.STRING, "message", "Announcement text; type \\n for a new line", true)
                .setMaxLength(FreebieBroadcasts.MAX_BODY));
        var section = new OptionData(OptionType.STRING, "section", "Only servers with this section's channel (default: every server's main channel)", false)
                .addChoice("Every server (main channel)", ALL);
        for (FreebieSection s : FreebieSection.values()) section.addChoice(s.label(), String.valueOf(s.code()));
        addOption(section);
        addOption(new OptionData(OptionType.STRING, "if-missing", "With a section: what to do for a server without that section", false)
                .addChoice("Skip that server", SKIP).addChoice("Use its main channel", MAIN));
    }

    static final String ALL = "all", SKIP = "skip", MAIN = "main";

    @Override public void execute(BunnyHub client, CommandContext ctx) {
        var system = FreebieSystem.current().orElse(null);
        String user = ctx.getUser().getId();
        if (system == null || !system.config().isOwner(user)) {
            ctx.reply(Embeds.of("📢", "Announcement", "Only active freebie owners can use this.").build(), true);
            return;
        }
        if (ctx.getChannel() == null || !system.config().isReviewChannel(ctx.getChannel().getId())) {
            ctx.reply(Embeds.of("📢", "Announcement", "Use this in a free-game review channel, where the confirmation button works.").build(), true);
            return;
        }
        ctx.defer(true);
        String section = ctx.getString("section");
        boolean fallback = MAIN.equals(ctx.getString("if-missing"));
        switch (system.announce(user, ctx.getString("title"), ctx.getString("message"), ALL.equals(section) ? null : section, fallback)) {
            case FreebieBroadcasts.Problem problem -> ctx.reply(Embeds.of("📢", "Announcement", problem.text()).build(), true);
            case FreebieBroadcasts.Preview preview -> ctx.replyMessage(preview.message());
        }
    }
}
