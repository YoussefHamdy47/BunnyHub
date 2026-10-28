package org.bunnys.bunnynexus.freebies;

import com.mongodb.client.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real MongoDB: owner announcements, one per subscribed server, confirmed once, stoppable and recoverable. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieBroadcastsIT {
    final Instant now = Instant.parse("2026-10-28T12:00:00Z");
    LocalReplicaSet replica;
    MongoDatabase db;
    FreebieRepository repository;
    FreebieSubscriptions subscriptions;
    FreebieBroadcasts broadcasts;
    final List<String> notices = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }
    @BeforeEach void fresh() {
        db = replica.client.getDatabase("broadcast_it_" + UUID.randomUUID().toString().replace("-", ""));
        repository = new FreebieRepository(db); repository.installIndexes();
        subscriptions = new FreebieSubscriptions(db); subscriptions.installIndexes();
        notices.clear();
        broadcasts = new FreebieBroadcasts(repository, subscriptions, Clock.fixed(now, ZoneOffset.UTC), notices::add);
        // Server A: mobile and major-PC channels; server B: one indie channel.
        subscriptions.save(new FreebieSubscriptions.Subscription("100000000000000001", "300000000000000009", FreebieStore.ANDROID, Optional.of("400000000000000001")), "a", now);
        subscriptions.save(new FreebieSubscriptions.Subscription("100000000000000001", "300000000000000005", FreebieStore.EPIC, Optional.empty()), "a", now);
        subscriptions.save(new FreebieSubscriptions.Subscription("100000000000000001", "300000000000000005", FreebieStore.STEAM, Optional.empty()), "a", now);
        subscriptions.save(new FreebieSubscriptions.Subscription("100000000000000002", "300000000000000007", FreebieStore.ITCHIO, Optional.empty()), "b", now);
    }

    String token(FreebieBroadcasts.Reply reply) {
        var preview = assertInstanceOf(FreebieBroadcasts.Preview.class, reply);
        String id = ((net.dv8tion.jda.api.components.buttons.Button) preview.message().getComponents().getFirst().asActionRow()
                .getComponents().getFirst()).getCustomId();
        return id;
    }
    static String action(String customId) { return customId.split(":", 3)[1]; }
    static String draftId(String customId) { return customId.split(":", 3)[2]; }
    MongoCollection<Document> jobs() { return db.getCollection(FreebieBroadcastStore.JOBS); }

    @Test void oneMessagePerServerPreferringTheMajorPcChannel() {
        assertEquals(Map.of("100000000000000001", "300000000000000005", "100000000000000002", "300000000000000007"), broadcasts.recipients());
    }

    @Test void previewSendsNothingAndOnlyThePreviewingOwnerCanConfirmOnce() throws Exception {
        String button = token(broadcasts.preview("owner", "Maintenance", "Down tonight\\nBack soon"));
        assertTrue(button.contains("Send") || button.startsWith(FreebieMessages.BUTTON_PREFIX));
        assertEquals(0, jobs().countDocuments(), "a preview sends nothing");
        String id = draftId(button), token = action(button).substring(FreebieBroadcasts.SEND.length());
        assertTrue(broadcasts.confirm("other-owner", id, token).startsWith("This draft expired"));
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Callable<String>> clicks = new ArrayList<>();
            for (int i = 0; i < 6; i++) clicks.add(() -> broadcasts.confirm("owner", id, token));
            long sending = 0;
            for (var f : pool.invokeAll(clicks)) if (f.get().startsWith("Sending")) sending++;
            assertEquals(1, sending);
        }
        assertEquals(2, jobs().countDocuments(), "one job per server");
        assertEquals("Down tonight\nBack soon", repository.broadcasts().get(id).orElseThrow().body());
    }

    @Test void thePingChoiceIsSavedWithTheConfirmationAndEachServerKeepsItsRole() {
        String button = token(broadcasts.preview("owner", "T", "B"));
        String id = draftId(button);
        String quiet = FreebieMessages.BUTTON_PREFIX + ":" + FreebieBroadcasts.SEND_QUIET;
        var preview = assertInstanceOf(FreebieBroadcasts.Preview.class, broadcasts.preview("owner", "T", "B"));
        var ids = preview.message().getComponents().getFirst().asActionRow().getComponents().stream()
                .map(c -> ((net.dv8tion.jda.api.components.buttons.Button) c).getCustomId()).toList();
        assertTrue(ids.stream().anyMatch(i -> i.startsWith(quiet)), "the preview offers Send without ping");
        assertTrue(broadcasts.confirm("owner", id, action(button).substring(FreebieBroadcasts.SEND.length()), true).contains("pinged"));
        assertTrue(repository.broadcasts().get(id).orElseThrow().ping());
        var roles = new HashMap<String, Object>();
        for (Document d : jobs().find()) roles.put(d.getString("guildId"), d.get("roleId"));
        assertNull(roles.get("100000000000000001"), "server A's chosen channel (major PC) has no saved role");
        assertNull(roles.get("100000000000000002"));
        // With a role saved on the chosen channel, that role travels with the job.
        subscriptions.save(new FreebieSubscriptions.Subscription("100000000000000001", "300000000000000005", FreebieStore.EPIC,
                Optional.of("400000000000000002")), "a", now);
        String second = token(broadcasts.preview("owner", "T2", "B2"));
        broadcasts.confirm("owner", draftId(second), action(second).substring(FreebieBroadcasts.SEND.length()), true);
        assertEquals("400000000000000002", jobs().find(eq("_id", draftId(second) + "|100000000000000001")).first().getString("roleId"));
    }

    Map<String, String> planned(FreebieBroadcasts.Target target) {
        String button = token(broadcasts.preview("owner", "T", "B", target));
        broadcasts.confirm("owner", draftId(button), action(button).substring(FreebieBroadcasts.SEND.length()), false);
        Map<String, String> channels = new TreeMap<>();
        for (Document d : jobs().find(eq("broadcastId", draftId(button)))) channels.put(d.getString("guildId"), d.getString("channelId"));
        return channels;
    }

    @Test void aSectionAnnouncementSkipsServersWithoutThatSection() {
        assertEquals(Map.of("100000000000000002", "300000000000000007"),
                planned(FreebieBroadcasts.target(String.valueOf(FreebieSection.OTHER_PC.code()), false)),
                "only server B has an indie channel; server A is skipped");
        assertEquals(Map.of("100000000000000001", "300000000000000009"),
                planned(FreebieBroadcasts.target(String.valueOf(FreebieSection.MOBILE.code()), false)),
                "server A's mobile channel, not its major PC one");
    }

    @Test void withFallbackServersWithoutTheSectionGetTheirMainChannel() {
        var target = FreebieBroadcasts.target(String.valueOf(FreebieSection.OTHER_PC.code()), true);
        var preview = assertInstanceOf(FreebieBroadcasts.Preview.class, broadcasts.preview("owner", "T", "B", target));
        assertTrue(preview.message().getEmbeds().get(1).getDescription().contains("1 in their main channel"));
        assertEquals(Map.of("100000000000000001", "300000000000000005", "100000000000000002", "300000000000000007"), planned(target));
    }

    @Test void aSectionNobodyHasExplainsHowToReachTheOthers() {
        var problem = assertInstanceOf(FreebieBroadcasts.Problem.class,
                broadcasts.preview("owner", "T", "B", FreebieBroadcasts.target(String.valueOf(FreebieSection.CONSOLE.code()), false)));
        assertTrue(problem.text().contains("main channel"));
    }

    @Test void recoveryAfterACrashPlansTheSameTargetThatWasPreviewed() {
        var target = FreebieBroadcasts.target(String.valueOf(FreebieSection.OTHER_PC.code()), false);
        String button = token(broadcasts.preview("owner", "T", "B", target));
        String id = draftId(button);
        repository.broadcasts().confirm(id, action(button).substring(FreebieBroadcasts.SEND.length()), "owner", now, false);
        broadcasts.housekeeping(); // planning after a crash between confirmation and planning
        assertEquals(1, jobs().countDocuments(eq("broadcastId", id)));
        assertEquals("100000000000000002", jobs().find(eq("broadcastId", id)).first().getString("guildId"));
    }

    @Test void expiredDraftsCannotBeSent() {
        String button = token(broadcasts.preview("owner", "T", "B"));
        var later = new FreebieBroadcasts(repository, subscriptions, Clock.fixed(now.plus(Duration.ofMinutes(16)), ZoneOffset.UTC), notices::add);
        assertTrue(later.confirm("owner", draftId(button), action(button).substring(FreebieBroadcasts.SEND.length())).startsWith("This draft expired"));
        assertEquals(0, jobs().countDocuments());
    }

    @Test void stoppingCancelsQueuedServersAndFinishingReportsOnce() {
        String button = token(broadcasts.preview("owner", "T", "B"));
        String id = draftId(button);
        broadcasts.confirm("owner", id, action(button).substring(FreebieBroadcasts.SEND.length()));
        var store = repository.broadcasts();
        var claimed = store.claim(now, Duration.ofMinutes(3)).orElseThrow();
        assertTrue(store.sent(claimed, "m1", now));
        assertTrue(broadcasts.stop("owner", id).startsWith("Stopped. 1 queued"));
        assertTrue(store.claim(now, Duration.ofMinutes(3)).isEmpty());
        assertEquals("CANCELLED", store.get(id).orElseThrow().state());
        assertTrue(broadcasts.stop("owner", id).startsWith("This announcement already"));
    }

    @Test void finishedBroadcastsAreReportedOnceAndPlanningResumesAfterACrash() {
        String button = token(broadcasts.preview("owner", "Hello", "World"));
        String id = draftId(button);
        var store = repository.broadcasts();
        // Crash between confirmation and planning: confirmed, no jobs.
        assertTrue(store.confirm(id, action(button).substring(FreebieBroadcasts.SEND.length()), "owner", now).isPresent());
        broadcasts.housekeeping();
        assertEquals(2, jobs().countDocuments(), "housekeeping planned it");
        for (int i = 0; i < 2; i++) store.sent(store.claim(now, Duration.ofMinutes(3)).orElseThrow(), "m" + i, now);
        broadcasts.housekeeping();
        broadcasts.housekeeping();
        assertEquals(1, notices.size());
        assertTrue(notices.getFirst().contains("2 sent"));
        assertEquals("DONE", store.get(id).orElseThrow().state());
    }

    @Test void anInterruptedSendIsRetriedWithHistoryVerification() {
        String button = token(broadcasts.preview("owner", "T", "B"));
        broadcasts.confirm("owner", draftId(button), action(button).substring(FreebieBroadcasts.SEND.length()));
        var store = repository.broadcasts();
        var claimed = store.claim(now, Duration.ofMinutes(3)).orElseThrow();
        store.submitting(claimed, now);
        assertEquals(1, new FreebieRepository(db).broadcasts().recover(now.plus(Duration.ofMinutes(4))));
        FreebieBroadcastStore.Job retry;
        do retry = store.claim(now.plus(Duration.ofMinutes(4)), Duration.ofMinutes(3)).orElseThrow();
        while (!retry.id().equals(claimed.id())); // The other server's job may come first.
        assertTrue(retry.verifyFirst(), "the retry must check history before sending again");
        assertFalse(store.sent(claimed, "late", now.plus(Duration.ofMinutes(4))), "a late result from the lost lease is ignored");
        assertEquals(2, jobs().countDocuments(and(exists("_id"))));
    }

    @Test void noSubscribedServersMeansNothingToSend() {
        db.getCollection(FreebieSubscriptions.COLLECTION).deleteMany(new Document());
        assertInstanceOf(FreebieBroadcasts.Problem.class, broadcasts.preview("owner", "T", "B"));
    }
}
