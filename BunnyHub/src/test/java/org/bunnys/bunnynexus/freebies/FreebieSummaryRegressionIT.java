package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.mongodb.client.model.Filters.eq;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** These assertions also run against the original summary path. No Discord connection is made. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FreebieSummaryRegressionIT {
    LocalReplicaSet replica;
    final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    @BeforeAll void start() throws Exception { replica = new LocalReplicaSet(); }
    @AfterAll void stop() { if (replica != null) replica.close(); }

    @Test void unavailableReviewChannelDoesNotRecordSuccess() throws Exception { attempt(false); }
    @Test void failedSendDoesNotRecordSuccess() throws Exception { attempt(true); }

    private void attempt(boolean available) throws Exception {
        var db = replica.client.getDatabase("summary_regression_" + UUID.randomUUID().toString().replace("-", ""));
        var repository = new FreebieRepository(db);
        repository.recordSeen(FreebieRepositoryIT.candidate("1", "PC, Steam"), now);
        var offer = repository.transition("gamerpower:1", FreebieOffer.State.PENDING,
                FreebieOffer.State.COMPLETED, null, now).orElseThrow();
        var client = mock(JDA.class);
        if (available) {
            var channel = mock(GuildMessageChannel.class);
            when(client.getChannelById(GuildMessageChannel.class, "100000000000000099")).thenReturn(channel);
            when(channel.canTalk()).thenReturn(true);
            var action = mock(MessageCreateAction.class, RETURNS_SELF);
            when(channel.sendMessage(any(MessageCreateData.class))).thenReturn(action);
            when(action.submit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("send failed")));
            doAnswer(call -> {
                java.util.function.Consumer<Throwable> failure = call.getArgument(1);
                failure.accept(new IllegalStateException("send failed"));
                return null;
            }).when(action).queue(any(), any());
        }
        var system = new FreebieSystem(new FreebieConfig("100000000000000099", Set.of("333644367539470337"), 10),
                repository, new FreebieSubscriptions(db), () -> client, Clock.fixed(now, ZoneOffset.UTC), new GamerPowerFeed());
        try {
            var field = FreebieSystem.class.getDeclaredField("reviews");
            field.setAccessible(true);
            ((FreebieReviewChannel) field.get(system)).summarize(offer);
            var executorField = FreebieSystem.class.getDeclaredField("sendingThread");
            executorField.setAccessible(true);
            var worker = (ExecutorService) executorField.get(system);
            for (int i = 0; i < 3; i++) worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        } finally { system.close(); }
        Document saved = db.getCollection(FreebieRepository.OFFERS).find(eq("_id", offer.id())).first();
        assertNotNull(saved);
        assertFalse(saved.getBoolean("summaryPosted", false), "no accepted Discord message means no durable success");
        assertNull(saved.getString("summaryMessageId"));
    }
}
