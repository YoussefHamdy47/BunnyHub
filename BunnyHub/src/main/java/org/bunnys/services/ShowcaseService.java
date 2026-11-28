package org.bunnys.services;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.bson.Document;
import org.bunnys.bunnynexus.updates.Showcase;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;
import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;

import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Posts the {@link Showcase} into {@code SHOWCASE_CHANNEL_ID} once per deployed build (Railway's
 * {@code RAILWAY_GIT_COMMIT_SHA}), two minutes after start so deals and free games are loaded. Without the variable
 * nothing happens. The builds already shown are kept in {@code ShowcasePosts}, so a restart never posts twice.
 */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class ShowcaseService implements BunnyService {
    static final long DELAY_SECONDS = 120;
    private ScheduledExecutorService executor;

    @Override public String name() { return "showcase"; }

    @Override public void start(BunnyHub client) {
        String channelId = TokenLoader.getEnvQuietly("SHOWCASE_CHANNEL_ID");
        if (channelId == null || !channelId.strip().matches("[0-9]{17,20}")) return;
        if (client.getMongoManager() == null || !client.getMongoManager().isConnected()) return;
        String build = version();
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("Showcase").daemon().factory());
        executor.schedule(() -> {
            try {
                JDA jda = client.getJDA();
                var channel = jda == null ? null : jda.getChannelById(GuildMessageChannel.class, channelId.strip());
                if (channel == null) { BunnyLog.warning("[Showcase] Channel " + channelId + " not found or not a server channel."); return; }
                var posts = client.getMongoManager().getCollection(Document.class, "ShowcasePosts");
                String key = channelId.strip() + ":" + build;
                // Claim this build first, so two starts of the same build never both post.
                var claim = posts.updateOne(Filters.eq("_id", key), Updates.setOnInsert("at", new Date()), new UpdateOptions().upsert(true));
                if (claim.getUpsertedId() == null) return;
                int posted = Showcase.post(channel, Showcase.messages(channel.getGuild().getId(), build));
                BunnyLog.info("[Showcase] Posted " + posted + " messages in " + channelId + " for build " + build + ".");
            } catch (RuntimeException failure) {
                BunnyLog.warning("[Showcase] Not posted | " + FailureDiagnostics.describe(failure));
            }
        }, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    /** The deployed commit's short hash, or "local". */
    static String version() {
        String sha = System.getenv("RAILWAY_GIT_COMMIT_SHA");
        return sha == null || sha.isBlank() ? "local" : sha.substring(0, Math.min(7, sha.length()));
    }

    @Override public void shutdown() { if (executor != null) executor.shutdownNow(); }
}
