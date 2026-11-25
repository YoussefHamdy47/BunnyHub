package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.bunnynexus.updates.Ad;
import org.bunnys.bunnynexus.updates.AdCards;
import org.bunnys.bunnynexus.updates.ProductUpdates;
import org.bunnys.bunnynexus.updates.UpdateAudience;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.cards.Card;
import org.bunnys.handler.commands.BunnySubcommand;
import org.bunnys.handler.commands.context.CommandContext;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * /freebie-admin ad-add | ad-list | ad-preview | ad-pause | ad-remove: the owner's ads under free-game alerts
 * (docs/product-updates/README.md, "Ads in alerts"). Owners only; every reply is private.
 */
public final class AdCommands {
    private AdCommands() {}

    public static BunnySubcommand add() { return new Add(); }
    public static BunnySubcommand list() { return new ListAds(); }
    public static BunnySubcommand preview() { return new Preview(); }
    public static BunnySubcommand pause() { return new Pause(); }
    public static BunnySubcommand remove() { return new Remove(); }

    private abstract static class Owner extends BunnySubcommand {
        @Override public final void execute(BunnyHub client, CommandContext ctx) {
            boolean owner = FreebieSystem.current().map(s -> s.config().isOwner(ctx.getUser().getId())).orElse(false);
            var updates = ProductUpdates.current().orElse(null);
            if (!owner || updates == null) {
                reply(ctx, !owner ? "Only bot owners can manage ads." : "Ads are not available right now.");
                return;
            }
            ctx.defer(true);
            run(ctx, updates);
        }
        abstract void run(CommandContext ctx, ProductUpdates updates);
        static void reply(CommandContext ctx, String text) { ctx.replyCard(Card.builder().text(text).build(), true); }
        static OptionData id(String what) { return new OptionData(OptionType.STRING, "id", what, true).setMaxLength(32); }
    }

    private static final class Add extends Owner {
        Add() {
            setName("ad-add");
            setDescription("Add an ad under free-game alerts, or replace the one with this id");
            addOption(id("Short name, like deals-board (lowercase, dashes)"));
            addOption(new OptionData(OptionType.STRING, "text", "What the ad says; \\n for a new line, {/free-games setup} for a command link", true).setMaxLength(Ad.MAX_TEXT));
            addOption(new OptionData(OptionType.STRING, "title", "A heading above the text", false).setMaxLength(Ad.MAX_TITLE));
            addOption(new OptionData(OptionType.STRING, "image", "An https link to a picture shown on top", false).setMaxLength(500));
            addOption(new OptionData(OptionType.STRING, "button-label", "Text on a link button", false).setMaxLength(Ad.MAX_LABEL));
            addOption(new OptionData(OptionType.STRING, "button-link", "Where the button goes (https)", false).setMaxLength(500));
            var audience = new OptionData(OptionType.STRING, "audience", "Who sees it (default: every server)", false);
            for (Ad.Audience a : Ad.Audience.values()) audience.addChoice(a.label, a.name());
            addOption(audience);
        }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            String id = ctx.getString("id"), text = lines(ctx.getString("text")), title = blank(ctx.getString("title")),
                    image = blank(ctx.getString("image")), label = blank(ctx.getString("button-label")), url = blank(ctx.getString("button-link"));
            var problem = Ad.problem(id, title, text, image, label, url);
            if (problem.isPresent()) { reply(ctx, problem.get()); return; }
            var audience = Optional.ofNullable(ctx.getString("audience")).map(Ad.Audience::valueOf).orElse(Ad.Audience.ALL);
            var ad = new Ad(id, Optional.ofNullable(title), text, Optional.ofNullable(image).map(i -> URI.create(i.strip())),
                    label == null ? Optional.empty() : Optional.of(new Ad.Link(label, URI.create(url.strip()))), audience, true, Instant.now());
            updates.ads().save(ad);
            ctx.replyMessage(AdCards.preview(ad, "Saved **" + id + "** for " + audience.label.toLowerCase() + ". It shows under free-game alerts, "
                    + "at most one ad per server every " + UpdateAudience.PROMO_EVERY.toDays() + " days and each ad at most "
                    + UpdateAudience.PROMO_TIMES + " times per server. This is how it looks:"), true);
        }

        private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
        private static String lines(String value) { return value == null ? null : value.replace("\\n", "\n").strip(); }
    }

    private static final class ListAds extends Owner {
        ListAds() { setName("ad-list"); setDescription("Every ad and whether it is showing"); }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            var all = updates.ads().all();
            if (all.isEmpty()) { reply(ctx, org.bunnys.handler.commands.CommandLinks.render("No ads yet. Add one with `/freebie-admin ad-add`.")); return; }
            var text = new StringBuilder();
            for (Ad ad : all)
                text.append(ad.on() ? "**On** · " : "Paused · ").append("`").append(ad.id()).append("` ")
                        .append(ad.title().orElse(ad.text().lines().findFirst().orElse(""))).append("\n-# ").append(ad.audience().label)
                        .append(ad.image().isPresent() ? " · image" : "").append(ad.link().map(l -> " · button \"" + l.label() + "\"").orElse(""))
                        .append("\n");
            ctx.replyCard(Card.builder().title("Ads").text(text.toString().strip()).build(), true);
        }
    }

    private static final class Preview extends Owner {
        Preview() { setName("ad-preview"); setDescription("Show an ad the way servers see it"); addOption(id("The ad's id")); }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            updates.ads().get(ctx.getString("id")).ifPresentOrElse(ad -> ctx.replyMessage(AdCards.preview(ad), true),
                    () -> reply(ctx, "No ad with that id."));
        }
    }

    private static final class Pause extends Owner {
        Pause() {
            setName("ad-pause");
            setDescription("Stop or resume showing an ad");
            addOption(id("The ad's id"));
            addOption(new OptionData(OptionType.BOOLEAN, "paused", "True to stop showing it, false to show it again", true));
        }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            boolean paused = Boolean.TRUE.equals(ctx.getBool("paused"));
            reply(ctx, updates.ads().setOn(ctx.getString("id"), !paused) ? (paused ? "Paused." : "Showing again.") : "No ad with that id.");
        }
    }

    private static final class Remove extends Owner {
        Remove() { setName("ad-remove"); setDescription("Delete an ad"); addOption(id("The ad's id")); }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            reply(ctx, updates.ads().remove(ctx.getString("id")) ? "Deleted. Alerts already posted keep it." : "No ad with that id.");
        }
    }
}
