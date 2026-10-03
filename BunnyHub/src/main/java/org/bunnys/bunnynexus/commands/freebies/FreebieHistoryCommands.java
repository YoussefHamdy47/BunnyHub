package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.handler.commands.context.SlashContext;
import org.bunnys.utils.Embeds;

/** Owner-only history and one-destination retry previews. */
public final class FreebieHistoryCommands extends BunnySubcommand {
    private final boolean retry;
    public FreebieHistoryCommands(boolean retry) {
        this.retry = retry;
        setName(retry ? "retry" : "history");
        setDescription(retry ? "Preview a retry for one failed or blocked destination" : "Private paginated delivery history for an offer");
        addOption(new OptionData(OptionType.STRING, "offer", "Offer ID (gamerpower:number)", true).setMaxLength(40));
        addOption(new OptionData(OptionType.STRING, "channel", "Destination ID; with history shows retry audit", retry).setMaxLength(20));
        if (!retry) addOption(new OptionData(OptionType.INTEGER, "audit-page", "Retry audit page (requires channel)", false).setRequiredRange(1, 20));
    }
    @Override public void execute(BunnyHub client, CommandContext ctx) {
        var system = FreebieSystem.current().orElse(null);
        if (system == null || !system.config().isOwner(ctx.getUser().getId())) {
            ctx.reply(Embeds.of("🎁", "Delivery history", "Only active freebie owners can use this.").build(), true); return;
        }
        if (!(ctx instanceof SlashContext slash)) return;
        ctx.defer(true);
        String offer = slash.event().getOption("offer").getAsString();
        String channel = slash.event().getOption("channel") == null ? "" : slash.event().getOption("channel").getAsString();
        if (!offer.matches("gamerpower:[0-9]{1,20}") || (!channel.isEmpty() && !channel.matches("[0-9]{17,20}"))) {
            ctx.reply(Embeds.of("🎁", "Delivery history", "Use a gamerpower:number offer ID and a numeric channel ID.").build(), true); return;
        }
        var view = retry ? system.deliveryAdmin().preview(ctx.getUser().getId(), offer, channel)
                : !channel.isEmpty() ? system.deliveryAdmin().audit(ctx.getUser().getId(), offer, channel,
                    slash.event().getOption("audit-page") == null ? 1 : slash.event().getOption("audit-page").getAsInt())
                : system.deliveryAdmin().history(ctx.getUser().getId(), offer, "");
        ctx.reply(Embeds.of("🎁", "Delivery history", view.text()).build(), view.rows(), true);
    }
}
