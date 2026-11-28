package org.bunnys.bunnynexus.updates;

import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.deals.DealCards;
import org.bunnys.bunnynexus.deals.DealSystem;
import org.bunnys.bunnynexus.freebies.FreebieMessages;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The owner's demo of every public card, as servers see them right now (owner request 2026-11-28): a free-game alert
 * with its ad, each ad filled with today's deals and games, a deal post and the Deals Board, the {@code /deals} and
 * {@code /free-games list} replies with an ad, and the welcome message. Posted once per deploy into
 * {@code SHOWCASE_CHANNEL_ID} ({@code ShowcaseService}); nothing is counted, recorded or pinged.
 */
public final class Showcase {
    private Showcase() {}

    static final long SPACING_MILLIS = 1200;

    /** The messages, in order. Blocking (database reads); call off the gateway thread. */
    public static List<MessageCreateData> messages(String guildId, String version) {
        var freebies = FreebieSystem.current().orElse(null);
        List<MessageCreateData> messages = new ArrayList<>();
        messages.add(note("## BunnyNexus showcase\nEvery public card as servers see them right now · build `" + version + "`. "
                + "Ads here are not counted."));
        if (freebies == null) {
            messages.add(note("Free-game alerts are not running, so there is nothing else to show."));
            return messages;
        }
        var live = freebies.liveNow(Optional.empty()).stream().filter(o -> !o.store().news()).toList();
        var page = live.stream().limit(2).toList();
        var show = ProductUpdates.current().map(u -> u.showcase(guildId, page));

        if (!page.isEmpty()) {
            var forAlert = show.flatMap(ProductUpdates.Showcase::forAlert);
            messages.add(note("### 1. A free-game alert, with the ad it would carry now" + forAlert.map(a -> " (`" + a.id() + "`)").orElse(" (no ad fits)")));
            messages.add(freebies.showcaseAlert(page, forAlert.map(AdCards::card)));
        } else messages.add(note("### 1. Free-game alert\nNothing is free right now, so there is no alert to show."));

        messages.add(note("### 2. Every ad, filled with today's deals and games"));
        show.ifPresent(s -> s.ads().forEach(e -> messages.add(AdCards.preview(e.getKey(), "`" + e.getKey().id() + "` · priority "
                + e.getKey().priority() + " · " + e.getKey().when().label.toLowerCase()
                + (e.getValue() ? "" : " · **nothing fits right now, so servers don't see it yet**")))));

        var deals = DealSystem.current().map(DealSystem::snapshot).filter(s -> !s.stale(Instant.now()) && !s.deals().isEmpty());
        if (deals.isPresent()) {
            var snapshot = deals.get();
            messages.add(note("### 3. Deals: a new-deals post, then the Deals Board"));
            messages.add(DealCards.post(snapshot, snapshot.deals().subList(0, Math.min(3, snapshot.deals().size())), false,
                    Optional.empty(), Optional.empty()));
            messages.add(DealCards.board(snapshot, DealCards.Notice.NONE));
            messages.add(note("### 4. `/deals` with an ad (a private reply in real use, at most one ad per person every 3 days)"));
            messages.add(AdCards.attach(DealCards.list(snapshot, Optional.empty(), 0), anyAd(show)));
        } else messages.add(note("### 3. Deals\nNo fresh deals right now."));

        messages.add(note("### 5. `/free-games list` with an ad (a private reply in real use)"));
        messages.add(AdCards.attach(FreebieMessages.liveList(live, Optional.empty(), freebies.upcoming()), anyAd(show)));

        messages.add(note("### 6. The welcome message when a server adds the bot (no ad)"));
        messages.add(org.bunnys.events.GuildWelcome.showcaseCard().message());
        return messages;
    }

    /** Posts the messages a second or so apart. Returns how many were posted. */
    public static int post(GuildMessageChannel channel, List<MessageCreateData> messages) {
        int posted = 0;
        for (var message : messages) {
            try {
                channel.sendMessage(message).setAllowedMentions(List.of()).submit().get(30, TimeUnit.SECONDS);
                posted++;
                Thread.sleep(SPACING_MILLIS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception failure) {
                BunnyLog.warning("[Showcase] A message failed | " + FailureDiagnostics.describe(failure));
            }
        }
        return posted;
    }

    /** The first ad that is filled right now, for the private-reply examples. */
    private static Optional<net.dv8tion.jda.api.components.container.Container> anyAd(Optional<ProductUpdates.Showcase> show) {
        return show.flatMap(s -> s.ads().stream().filter(Map.Entry::getValue).findFirst()).map(e -> AdCards.card(e.getKey()));
    }

    private static MessageCreateData note(String text) {
        return new MessageCreateBuilder().useComponentsV2(true).setComponents(TextDisplay.of(text)).setAllowedMentions(List.of()).build();
    }
}
