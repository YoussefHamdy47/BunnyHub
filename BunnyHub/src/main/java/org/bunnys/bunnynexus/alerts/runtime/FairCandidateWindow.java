package org.bunnys.bunnynexus.alerts.runtime;

import org.bunnys.bunnynexus.alerts.domain.DeliveryJob;
import java.util.*;

/** Disposable bounded hints, one per channel, rotating first by guild and then channel. Never durable work. */
public final class FairCandidateWindow {
    private final int capacity;
    private final LinkedHashMap<String, LinkedHashMap<String, DeliveryJob>> guilds = new LinkedHashMap<>();
    private final Set<String> ids = new HashSet<>();
    public FairCandidateWindow(int capacity) {
        if (capacity < 1 || capacity > 5000) throw new IllegalArgumentException("Candidate window must be 1 to 5000.");
        this.capacity = capacity;
    }
    public synchronized boolean offer(DeliveryJob job) {
        Objects.requireNonNull(job);
        if (job.state() != DeliveryJob.State.READY && job.state() != DeliveryJob.State.RETRY_WAIT) throw new IllegalArgumentException("Not a due-state candidate.");
        if (ids.contains(job.id())) return false;
        var channels = guilds.get(job.destination().guildId());
        if (channels != null && channels.containsKey(job.destination().channelId())) return false;
        if (ids.size() == capacity) return false;
        guilds.computeIfAbsent(job.destination().guildId(), ignored -> new LinkedHashMap<>()).put(job.destination().channelId(), job);
        ids.add(job.id()); return true;
    }
    public synchronized Optional<DeliveryJob> poll() {
        if (guilds.isEmpty()) return Optional.empty();
        var guild = guilds.entrySet().iterator().next(); var channels = guild.getValue();
        var channel = channels.entrySet().iterator().next(); DeliveryJob job = channel.getValue();
        channels.remove(channel.getKey()); guilds.remove(guild.getKey()); ids.remove(job.id());
        if (!channels.isEmpty()) guilds.put(guild.getKey(), channels);
        return Optional.of(job);
    }
    public synchronized int size() { return ids.size(); }
    public synchronized boolean full() { return ids.size() == capacity; }
    public synchronized void clear() { guilds.clear(); ids.clear(); }
}
