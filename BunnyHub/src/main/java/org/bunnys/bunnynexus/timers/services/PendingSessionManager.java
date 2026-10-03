package org.bunnys.bunnynexus.timers.services;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.bunnys.bunnynexus.timers.buttons.SessionMenuManager;
import org.bunnys.bunnynexus.timers.SessionEmbeds;
import org.bunnys.utils.Embeds;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public final class PendingSessionManager {
    public static final int MAX_SESSIONS = 1024;
    private PendingSessionManager() {}

    private static final Map<String, PendingSession> PENDING_SESSIONS = new ConcurrentHashMap<>();

    private static final ScheduledThreadPoolExecutor SCHEDULER = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "Pending-Session-Timer");
        thread.setDaemon(true);
        return thread;
    });
    static { SCHEDULER.setRemoveOnCancelPolicy(true); }

    public static synchronized boolean createPendingSession(String userId, String topic, String objective, String channelId,
            String guildId, InteractionHook hook, String messageId) {
        if (SCHEDULER.isShutdown() || (!PENDING_SESSIONS.containsKey(userId) && PENDING_SESSIONS.size() >= MAX_SESSIONS))
            return false;
        cancelPendingSession(userId);

        PendingSession session = new PendingSession(userId, topic, objective, channelId, guildId, hook);
        session.messageId = messageId;
        PENDING_SESSIONS.put(userId, session);

        session.reminderTask = SCHEDULER.schedule(() -> {
            if (PENDING_SESSIONS.get(userId) != session) return;
            // The ping lives in the message content; mentions inside embeds never notify.
            hook.sendMessage("<@" + userId + ">").addEmbeds(Embeds.of("⏰", "Still waiting for you", "Your session for **"
                            + SubjectTopics.code(topic) + "** is ready. Click **Start** when you are, or it closes in 5 minutes.").build())
                    .queue(msg -> {
                        synchronized (PendingSessionManager.class) {
                            if (PENDING_SESSIONS.get(userId) == session) session.reminderMessageId = msg.getId();
                            else msg.delete().queue(null, ignored -> {});
                        }
                    }, ignored -> {});
        }, 5, TimeUnit.MINUTES);

        session.timeoutTask = SCHEDULER.schedule(() -> {
            if (!PENDING_SESSIONS.remove(userId, session)) return;

            if (session.reminderMessageId != null) {
                session.hook.deleteMessageById(session.reminderMessageId).queue(null, ignored -> {});
            }

            hook.editOriginalEmbeds(SessionEmbeds.timedOut(topic))
                    .setComponents(ActionRow.of(
                            SessionMenuManager.buildButtons(userId, SessionMenuManager.SessionState.ENDED)))
                    .queue(null, ignored -> {});

        }, 10, TimeUnit.MINUTES);
        return true;
    }

    private static synchronized PendingSession getAndRemove(String userId) {
        PendingSession session = PENDING_SESSIONS.remove(userId);
        if (session != null) {
            if (session.reminderTask != null)
                session.reminderTask.cancel(false);
            if (session.timeoutTask != null)
                session.timeoutTask.cancel(false);
            if (session.reminderMessageId != null && session.hook != null)
                session.hook.deleteMessageById(session.reminderMessageId).queue(null, ignored -> {});
        }
        return session;
    }

    /** @return whether a pending session existed and was cancelled */
    public static synchronized boolean cancelPendingSession(String userId) {
        var previous = getAndRemove(userId);
        if (previous != null) previous.hook.editOriginalComponents().queue(null, ignored -> {});
        return previous != null;
    }

    public static synchronized boolean matches(String userId, String messageId) {
        var session = PENDING_SESSIONS.get(userId);
        return session != null && messageId.equals(session.messageId);
    }

    public static synchronized PendingSession getAndRemove(String userId, String messageId) {
        return matches(userId, messageId) ? getAndRemove(userId) : null;
    }

    public static synchronized void shutdown() {
        for (String userId : List.copyOf(PENDING_SESSIONS.keySet())) getAndRemove(userId);
        SCHEDULER.shutdownNow();
    }

    public static class PendingSession {
        public final String userId, topic, objective, channelId, guildId;
        public final InteractionHook hook;
        public ScheduledFuture<?> reminderTask;
        public ScheduledFuture<?> timeoutTask;
        public volatile String reminderMessageId;
        public String messageId;

        public PendingSession(String userId, String topic, String objective, String channelId, String guildId,
                InteractionHook hook) {
            this.userId = userId;
            this.topic = topic;
            this.objective = objective;
            this.channelId = channelId;
            this.guildId = guildId;
            this.hook = hook;
        }
    }
}
