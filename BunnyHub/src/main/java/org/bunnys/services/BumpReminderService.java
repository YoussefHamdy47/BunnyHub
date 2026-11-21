package org.bunnys.services;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bson.Document;
import org.bunnys.bunnynexus.bump.BumpReminders;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;
import org.bunnys.utils.BunnyLog;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * "Time to bump" by DM to the bot owner (the first developer ID) every two hours. Always on; {@code BUMP_REMINDERS=false}
 * turns it off. The last DM's time is one document in {@code BumpReminders}.
 */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class BumpReminderService implements BunnyService {
    private BumpReminders reminders;
    private ScheduledExecutorService executor;

    @Override public String name() { return "bump reminders"; }

    @Override public synchronized void start(BunnyHub client) {
        if ("false".equalsIgnoreCase(System.getenv("BUMP_REMINDERS"))) { BunnyLog.info("[Bump] Off (BUMP_REMINDERS=false)."); return; }
        var owners = client.getCommandRegistry().getDeveloperIds();
        if (owners.isEmpty() || !client.getMongoManager().isConnected()) { BunnyLog.warning("[Bump] Off: no owner ID or no database."); return; }
        String owner = owners.iterator().next();
        var state = client.getMongoManager().getCollection(Document.class, "BumpReminders");
        var id = Filters.eq("_id", "owner");
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("BunnyBump").daemon().factory());
        reminders = new BumpReminders(data -> dm(client, owner, data),
                () -> Optional.ofNullable(state.find(id).first()).map(d -> d.getDate("lastSent")).map(Date::toInstant),
                at -> state.replaceOne(id, new Document("_id", "owner").append("lastSent", Date.from(at)), new ReplaceOptions().upsert(true)),
                executor, Clock.systemUTC());
        startWhenConnected(client, 0);
    }

    /** A DM needs the gateway; checks every 10 s for up to 5 minutes. */
    private void startWhenConnected(BunnyHub client, int tries) {
        executor.schedule(() -> {
            JDA jda = client.getJDA();
            if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) reminders.start();
            else if (tries < 30) startWhenConnected(client, tries + 1);
            else BunnyLog.warning("[Bump] Discord never connected; reminders not started.");
        }, 10, TimeUnit.SECONDS);
    }

    private static CompletableFuture<Void> dm(BunnyHub client, String owner, MessageCreateData data) {
        JDA jda = client.getJDA();
        if (jda == null) return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
        return jda.openPrivateChannelById(owner).flatMap(channel -> channel.sendMessage(data)).submit().thenApply(sent -> null);
    }

    @Override public synchronized void shutdown() {
        if (reminders != null) reminders.shutdown();
        else if (executor != null) executor.shutdownNow();
    }
}
