package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.bson.Document;
import org.bunnys.handler.cards.Card;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/**
 * Console giveaways the free-game feeds miss. Every hour, news headlines that look like a console game free to keep
 * ({@link ConsoleHeadlines}) become a tip in the review channel. The owner adds the game with its store link: for Xbox
 * the Microsoft Store must show it at $0 with a normal price above $0 ({@link XboxStore}), and gives the title, picture
 * and end date; a PlayStation link needs the title typed in. The game then gets a normal review card, and posts like
 * any free game once approved. Tips are remembered in {@code ConsoleTips}, so each headline is posted once.
 */
final class ConsoleTips implements AutoCloseable {
    static final String COLLECTION = "ConsoleTips", BUTTON = "console_tip", MODAL = "console_tip_form";
    static final String LINK_FIELD = "link", TITLE_FIELD = "title", ENDS_FIELD = "ends";
    static final Duration MAX_AGE = Duration.ofDays(3), KEEP = Duration.ofDays(30);
    static final int PER_RUN = 5, MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern PS_STORE = Pattern.compile("^/[a-z]{2}-[a-z]{2}/(product|concept)/([A-Za-z0-9_-]{1,64})/?$");
    private static final Set<String> HOSTS = Set.of(XboxStore.HOST, "blog.playstation.com", "www.pushsquare.com", "www.purexbox.com");

    /** {@link ConditionalFetch} in production. */
    interface Http {
        /** Empty: unchanged since the last committed read. */
        Optional<byte[]> ifChanged(URI url) throws IOException;
        byte[] get(URI url) throws IOException;
        void commit();
        void rollback();
    }
    interface Review { boolean post(FreebieStore store, MessageCreateData message); }

    private final MongoCollection<Document> tips;
    private final Http http;
    private final Review review;
    private final FreebieRepository repository;
    private final Runnable newGame;
    private final Clock clock;
    private final AutoCloseable closer;

    ConsoleTips(MongoCollection<Document> tips, Http http, AutoCloseable closer, Review review, FreebieRepository repository,
                Runnable newGame, Clock clock) {
        this.tips = tips; this.http = http; this.closer = closer; this.review = review; this.repository = repository;
        this.newGame = newGame; this.clock = clock;
    }

    /** Production HTTP: only the news feeds and the Microsoft Store catalog, public addresses, no redirects. */
    static ConsoleTips create(MongoCollection<Document> tips, Review review, FreebieRepository repository, Runnable newGame, Clock clock) {
        OkHttpClient client = FreebieHttp.client(host -> {
            if (!HOSTS.contains(host)) throw new UnknownHostException("Unapproved console tip host");
            var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
            if (addresses.isEmpty()) throw new UnknownHostException("No public console tip address");
            return addresses;
        }, Duration.ofSeconds(20));
        var calls = new FreebieHttp.Calls();
        var fetch = new ConditionalFetch(client, calls, "BunnyNexus-Freebies/2.0", MAX_BYTES);
        Http http = new Http() {
            @Override public Optional<byte[]> ifChanged(URI url) throws IOException { return fetch.ifChanged(url); }
            @Override public byte[] get(URI url) throws IOException { return fetch.get(url); }
            @Override public void commit() { fetch.commit(); }
            @Override public void rollback() { fetch.rollback(); }
        };
        return new ConsoleTips(tips, http, calls, review, repository, newGame, clock);
    }

    static String id(String link) { return FreebieMessages.nonce(link).substring(0, 16); }

    /**
     * Reads every news feed (an unchanged one costs a 304 and is skipped), then posts up to {@link #PER_RUN} new tips.
     * Returns how many were posted.
     */
    int run() {
        Instant now = clock.instant();
        try {
            for (var source : ConsoleHeadlines.SOURCES) {
                Optional<byte[]> body;
                try { body = http.ifChanged(source.feed()); }
                catch (IOException | RuntimeException failure) {
                    BunnyLog.warning("[Freebies] Console tips: " + source.name() + " unreadable | " + FailureDiagnostics.describe(failure));
                    continue;
                }
                if (body.isEmpty()) continue;
                for (var h : ConsoleHeadlines.parse(body.get(), source)) {
                    if (!h.published().isAfter(now.minus(MAX_AGE)) || h.published().isAfter(now.plus(Duration.ofDays(1)))) continue;
                    tips.updateOne(eq("_id", id(h.link())), new Document("$setOnInsert", new Document("link", h.link()).append("title", h.title())
                            .append("source", h.source().name()).append("store", h.source().store().id()).append("published", Date.from(h.published()))
                            .append("foundAt", Date.from(now)).append("state", "new")), new UpdateOptions().upsert(true));
                }
            }
            // Tips older than a month are only history.
            tips.deleteMany(lt("foundAt", Date.from(now.minus(KEEP))));
        } catch (RuntimeException failure) {
            http.rollback();
            throw failure;
        }
        http.commit();
        int posted = 0;
        for (Document tip : tips.find(eq("state", "new")).sort(new Document("foundAt", 1)).limit(PER_RUN)) {
            var store = FreebieStore.byId(tip.getString("store")).orElse(FreebieStore.PLAYSTATION);
            if (!review.post(store, card(tip))) break;
            tips.updateOne(eq("_id", tip.getString("_id")), combine(set("state", "posted"), set("postedAt", Date.from(now))));
            posted++;
        }
        if (posted > 0) BunnyLog.success("[Freebies] Console tips posted for review: " + posted);
        return posted;
    }

    static MessageCreateData card(Document tip) {
        String id = tip.getString("_id");
        return Card.builder().text("**Possible console giveaway** · " + tip.getString("source") + "\n["
                        + MarkdownSanitizer.escape(tip.getString("title")).replace("[", "(").replace("]", ")") + "](" + tip.getString("link") + ")"
                        + "\n-# If it's a game people keep for free, add it with its store link. An Xbox link is checked against the "
                        + "Microsoft Store price. The game then gets a normal review card.")
                .buttons(Button.primary(BUTTON + ":add:" + id, "Add as free game"), Button.secondary(BUTTON + ":dismiss:" + id, "Not a giveaway"))
                .build().message();
    }

    /** The owner's form for one tip. */
    static Modal form(String tipId) {
        var link = TextInput.create(LINK_FIELD, TextInputStyle.SHORT).setRequired(true).setMaxLength(300)
                .setPlaceholder("https://www.xbox.com/en-US/games/store/… or https://store.playstation.com/en-us/…").build();
        var title = TextInput.create(TITLE_FIELD, TextInputStyle.SHORT).setRequired(false).setMaxLength(150).build();
        var ends = TextInput.create(ENDS_FIELD, TextInputStyle.SHORT).setRequired(false).setMaxLength(16).setPlaceholder("2026-12-03 16:00").build();
        return Modal.create(MODAL + ":" + tipId, "Add console free game")
                .addComponents(Label.of("Store link", "The game's own page on the Xbox or PlayStation store", link),
                        Label.of("Game title", "Needed for PlayStation; Xbox fills it in", title),
                        Label.of("Free until (UTC)", "Optional. Xbox fills it in when the store says", ends))
                .build();
    }

    /** Outcome of the form: {@code added} when the game is waiting for review. */
    record Added(boolean added, String text) {}

    Added add(String tipId, String linkText, String typedTitle, String typedEnds) {
        var tip = tips.find(eq("_id", tipId)).first();
        if (tip == null) return new Added(false, "That tip is no longer known.");
        if ("added".equals(tip.getString("state"))) return new Added(false, "This tip was already added.");
        URI link;
        try { link = URI.create(linkText.strip()); }
        catch (RuntimeException bad) { return new Added(false, "That isn't a link."); }
        Optional<Instant> ends;
        try { ends = ends(typedEnds); }
        catch (RuntimeException bad) { return new Added(false, "Write the end as 2026-12-03 or 2026-12-03 16:00 (UTC)."); }
        Instant now = clock.instant();
        if (ends.isPresent() && !ends.get().isAfter(now)) return new Added(false, "That end time has already passed.");

        FreebieFeed.Candidate candidate;
        var xbox = XboxStore.productId(link);
        if (xbox.isPresent()) {
            Optional<XboxStore.Price> price;
            try { price = XboxStore.price(http.get(URI.create(XboxStore.url(xbox.get()))), xbox.get()); }
            catch (IOException | RuntimeException failure) { return new Added(false, "The Microsoft Store didn't answer; try again in a minute."); }
            if (price.isEmpty()) return new Added(false, "The Microsoft Store lists no Xbox price for that game.");
            var p = price.get();
            if (!p.freeToKeep()) return new Added(false, "The Microsoft Store price is " + money(p.listPrice()) + " (normally " + money(p.msrp())
                    + "), so it isn't free to keep right now.");
            String title = typedTitle == null || typedTitle.isBlank() ? p.title() : typedTitle.strip();
            candidate = candidate(FreebieStore.XBOX, "xbox:" + p.productId(), title, p.storePage(), tip, Optional.of(money(p.msrp())), p.image(),
                    ends.or(p::ends));
        } else if ("store.playstation.com".equals(link.getHost()) && "https".equals(link.getScheme()) && link.getPath() != null
                && PS_STORE.matcher(link.getPath()).matches()) {
            if (typedTitle == null || typedTitle.isBlank()) return new Added(false, "Type the game's title for a PlayStation link.");
            var m = PS_STORE.matcher(link.getPath());
            if (!m.matches()) return new Added(false, "That PlayStation Store link isn't a game page.");
            candidate = candidate(FreebieStore.PLAYSTATION, "ps:" + m.group(2), typedTitle.strip(), URI.create("https://store.playstation.com" + link.getPath()),
                    tip, Optional.empty(), Optional.empty(), ends);
        } else return new Added(false, "Use the game's page on xbox.com, microsoft.com or store.playstation.com.");

        if (!repository.recordSeen(candidate, now)) return new Added(false, "That game is already known; check the review channel.");
        tips.updateOne(eq("_id", tipId), combine(set("state", "added"), set("addedAt", Date.from(now)), set("gameTitle", candidate.title())));
        newGame.run();
        return new Added(true, "Added **" + MarkdownSanitizer.escape(candidate.title()) + "** (" + candidate.store().label()
                + "). Its review card follows.");
    }

    String dismiss(String tipId) {
        tips.updateOne(eq("_id", tipId), combine(set("state", "dismissed"), set("dismissedAt", Date.from(clock.instant()))));
        return "Dismissed: not a giveaway.";
    }

    private static FreebieFeed.Candidate candidate(FreebieStore store, String sourceId, String title, URI page, Document tip,
                                                   Optional<String> worth, Optional<URI> image, Optional<Instant> ends) {
        return new FreebieFeed.Candidate(FreebieFeed.Provider.CONSOLE, sourceId, store, store == FreebieStore.XBOX ? "Xbox" : "PlayStation",
                title, "", "Add it to your library on the " + (store == FreebieStore.XBOX ? "Microsoft Store" : "PlayStation Store")
                        + " while it's free, and it stays yours.",
                worth, image, page, URI.create(tip.getString("link")), Optional.empty(), ends,
                "US store; other regions may differ", Optional.of(page));
    }

    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    static Optional<Instant> ends(String typed) {
        if (typed == null || typed.isBlank()) return Optional.empty();
        String t = typed.strip();
        if (t.length() == 10) return Optional.of(LocalDate.parse(t).atTime(23, 59).toInstant(ZoneOffset.UTC));
        return Optional.of(LocalDateTime.parse(t, DAY_TIME).toInstant(ZoneOffset.UTC));
    }

    static String money(double dollars) { return String.format(Locale.ROOT, "$%.2f", dollars); }

    @Override public void close() {
        try { closer.close(); } catch (Exception ignored) { /* shutting down */ }
    }
}
