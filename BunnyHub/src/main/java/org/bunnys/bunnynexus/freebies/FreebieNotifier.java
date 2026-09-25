package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.bunnys.utils.BunnyLog;
import org.bunnys.utils.FailureDiagnostics;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Tells a server's owner that their alert channel is broken: DM first, then the server's system channel.
 * Rate limited to one notice per server per day so a broken setup never turns into spam.
 * Database writes run on the freebie executor, never on JDA callback threads.
 */
final class FreebieNotifier {
    private final FreebieRepository repository;
    private final Supplier<JDA> jda;
    private final Clock clock;
    private final Executor executor;

    FreebieNotifier(FreebieRepository repository, Supplier<JDA> jda, Clock clock, Executor executor) {
        this.repository = repository; this.jda = jda; this.clock = clock; this.executor = executor;
    }

    void deliveryFailed(FreebieRepository.Claimed job, FreebieOffer offer, FreebieFailure failure) {
        if (!failure.notifyServer()) return;
        JDA client = jda.get();
        Guild guild = client == null ? null : client.getGuildById(job.guildId());
        if (guild == null || !repository.claimServerNotice(job.guildId(), clock.instant())) return;
        var notice = FreebieMessages.serverNotice(guild.getName(), job.channelId(), failure, offer);
        guild.retrieveOwner()
                .flatMap(owner -> owner.getUser().openPrivateChannel())
                .flatMap(dm -> dm.sendMessage(notice)).submit()
                .whenCompleteAsync((sent, dmFailed) -> {
                    if (dmFailed == null) { repository.markServerNotified(job.id()); return; }
                    var fallback = guild.getSystemChannel();
                    if (fallback == null || !fallback.canTalk() || fallback.getId().equals(job.channelId())) {
                        BunnyLog.warning("[Freebies] Server " + job.guildId() + " owner has DMs closed and no usable system channel.");
                        return;
                    }
                    fallback.sendMessage(notice).submit().whenCompleteAsync((ok, error) -> {
                        if (error == null) repository.markServerNotified(job.id());
                        else BunnyLog.warning("[Freebies] Could not notify server " + job.guildId() + " | " + FailureDiagnostics.describe(error));
                    }, executor);
                }, executor);
    }
}
