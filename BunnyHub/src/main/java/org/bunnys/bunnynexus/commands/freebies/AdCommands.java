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
import java.util.ArrayList;
import java.util.List;
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
            addOption(new OptionData(OptionType.STRING, "emoji", "One emoji before the title, like 🎮", false).setMaxLength(64));
            for (int i = 1; i <= Ad.MAX_LINKS; i++) {
                String n = i == 1 ? "button" : "button" + i;
                addOption(new OptionData(OptionType.STRING, n + "-label", "Text on link button " + i, false).setMaxLength(Ad.MAX_LABEL));
                addOption(new OptionData(OptionType.STRING, n + "-link", "Where button " + i + " goes (https)", false).setMaxLength(500));
                addOption(new OptionData(OptionType.STRING, n + "-emoji", "An emoji on button " + i, false).setMaxLength(64));
            }
            var audience = new OptionData(OptionType.STRING, "audience", "Who sees it (default: every server)", false);
            for (Ad.Audience a : Ad.Audience.values()) audience.addChoice(a.label, a.name());
            addOption(audience);
            var when = new OptionData(OptionType.STRING, "when", "The moment it is for (default: any free-game alert)", false);
            for (Ad.When w : Ad.When.values()) when.addChoice(w.label, w.name());
            addOption(when);
            addOption(new OptionData(OptionType.INTEGER, "priority", "Higher shows first when several ads fit (default 0; the bot's live ads use 15-30)", false)
                    .setRequiredRange(0, 100));
        }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            String id = ctx.getString("id"), text = lines(ctx.getString("text")), title = blank(ctx.getString("title")),
                    image = blank(ctx.getString("image")), emoji = blank(ctx.getString("emoji"));
            List<String[]> buttons = new ArrayList<>();
            for (int i = 1; i <= Ad.MAX_LINKS; i++) {
                String n = i == 1 ? "button" : "button" + i;
                String label = blank(ctx.getString(n + "-label")), url = blank(ctx.getString(n + "-link"));
                if (label != null || url != null) buttons.add(new String[]{label, url, blank(ctx.getString(n + "-emoji"))});
            }
            var problem = Ad.problems(id, title, text, image, emoji, buttons);
            if (problem.isPresent()) { reply(ctx, problem.get()); return; }
            var audience = Optional.ofNullable(ctx.getString("audience")).map(Ad.Audience::valueOf).orElse(Ad.Audience.ALL);
            var when = Optional.ofNullable(ctx.getString("when")).map(Ad.When::valueOf).orElse(Ad.When.ANY);
            int priority = Optional.ofNullable(ctx.getInt("priority")).orElse(0);
            var ad = new Ad(id, Optional.ofNullable(title), text, Optional.ofNullable(image).map(i -> URI.create(i.strip())),
                    buttons.stream().map(b -> new Ad.Link(b[0], b[1].strip(), Optional.ofNullable(b[2]))).toList(),
                    audience, true, Instant.now(), Optional.ofNullable(emoji), when, priority);
            updates.ads().save(ad);
            String note = "Saved **" + id + "** for " + audience.label.toLowerCase() + " · " + when.label.toLowerCase() + " · priority " + priority
                    + ". At most one ad per server every " + UpdateAudience.PROMO_EVERY.toDays() + " days, each ad at most "
                    + UpdateAudience.PROMO_TIMES + " times per server.";
            show(ctx, updates, ad, note);
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
                        .append(ad.emoji().map(e -> e + " ").orElse("")).append(ad.title().orElse(ad.text().lines().findFirst().orElse("")))
                        .append("\n-# ").append(ad.audience().label).append(ad.image().isPresent() ? " · image" : "")
                        .append(ad.links().isEmpty() ? "" : " · buttons: " + String.join(", ", ad.links().stream().map(Ad.Link::label).toList()))
                        .append("\n");
            ctx.replyCard(Card.builder().title("Ads").text(text.toString().strip()).build(), true);
        }
    }

    private static final class Preview extends Owner {
        Preview() { setName("ad-preview"); setDescription("Show an ad the way servers see it"); addOption(id("The ad's id")); }

        @Override void run(CommandContext ctx, ProductUpdates updates) {
            updates.ads().get(ctx.getString("id")).ifPresentOrElse(ad -> show(ctx, updates, ad, null),
                    () -> reply(ctx, "No ad with that id."));
        }
    }

    /** A live ad filled with what is on sale or free right now; when nothing fits now, says so and shows the template. */
    static void show(CommandContext ctx, ProductUpdates updates, Ad ad, String note) {
        var filled = updates.filled(ad, ctx.getGuild() == null ? "" : ctx.getGuild().getId());
        String status = !ad.live() ? "" : filled.isPresent() ? "Live ad: filled with what is happening right now."
                : "Live ad: nothing fits it right now (no matching deal, free game or total), so servers don't see it yet. The template:";
        String text = String.join("\n", java.util.stream.Stream.of(note, status).filter(s -> s != null && !s.isEmpty()).toList());
        var shown = filled.orElse(ad);
        ctx.replyMessage(text.isEmpty() ? AdCards.preview(shown) : AdCards.preview(shown, text), true);
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
