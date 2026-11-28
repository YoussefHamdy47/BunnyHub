package org.bunnys.services;

import net.dv8tion.jda.api.JDA;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.bunnys.bunnynexus.freebies.FreebieHttp;
import org.bunnys.bunnynexus.freebies.GamerPowerFeed;
import org.bunnys.handler.BunnyHub;
import org.bunnys.handler.BunnyService;
import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the bot's server count to top.gg every 30 minutes, so the top.gg page and its README widget show it. Only with
 * {@code TOPGG_TOKEN} set (top.gg → the bot's page → Integrations &amp; API); without it, nothing is sent. The token is
 * only ever sent to top.gg and never logged.
 */
@SuppressWarnings("unused") // Discovered reflectively by BunnyServices.
public final class TopggStats implements BunnyService {
    static final String HOST = "top.gg";
    static final Duration EVERY = Duration.ofMinutes(30);
    private ScheduledExecutorService executor;
    private final FreebieHttp.Calls calls = new FreebieHttp.Calls();
    private int lastSent = -1;

    @Override public String name() { return "top.gg stats"; }

    @Override public void start(BunnyHub client) {
        String token = TokenLoader.getEnvQuietly("TOPGG_TOKEN");
        if (token == null) { BunnyLog.info("[TopGG] Off: TOPGG_TOKEN is not set."); return; }
        var http = FreebieHttp.client(host -> {
            if (!HOST.equals(host)) throw new UnknownHostException("Unapproved host");
            var addresses = Dns.SYSTEM.lookup(host).stream().filter(GamerPowerFeed::isPublic).toList();
            if (addresses.isEmpty()) throw new UnknownHostException("No public address");
            return addresses;
        }, Duration.ofSeconds(20));
        executor = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("TopGG").daemon().factory());
        executor.scheduleWithFixedDelay(() -> {
            try {
                JDA jda = client.getJDA();
                if (jda == null || jda.getStatus() != JDA.Status.CONNECTED) return;
                int servers = (int) jda.getGuildCache().size();
                if (servers == lastSent) return; // Unchanged: nothing to send.
                var request = new Request.Builder().url("https://" + HOST + "/api/bots/" + jda.getSelfUser().getId() + "/stats")
                        .header("Authorization", token).header("User-Agent", "BunnyNexus (Discord bot)")
                        .post(RequestBody.create(body(servers), MediaType.get("application/json"))).build();
                int status = calls.execute(http, request, response -> response.code());
                if (status == 200) lastSent = servers;
                else BunnyLog.warning("[TopGG] Server count not accepted: HTTP " + status);
            } catch (Exception failure) {
                BunnyLog.warning("[TopGG] Server count not sent: " + failure.getClass().getSimpleName());
            }
        }, 2, EVERY.toMinutes(), TimeUnit.MINUTES);
        BunnyLog.success("[TopGG] Sending the server count every 30 minutes.");
    }

    static String body(int servers) { return "{\"server_count\":" + servers + "}"; }

    @Override public void shutdown() {
        if (executor != null) executor.shutdownNow();
        calls.close();
    }
}
