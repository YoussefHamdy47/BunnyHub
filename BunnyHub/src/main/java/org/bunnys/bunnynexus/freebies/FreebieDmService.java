package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Subscribe/unsubscribe for personal DM alerts, used by {@code /free-games subscribe|unsubscribe} and the
 * Unsubscribe button. Blocking database work; command workers only.
 */
public final class FreebieDmService {
    public enum Subscribed { SUBSCRIBED, EDITED, FULL, DISABLED, BUSY, DM_REFUSED }

    private final FreebieConfig config;
    private final FreebieRepository repository;
    private final Clock clock;

    FreebieDmService(FreebieConfig config, FreebieRepository repository, Clock clock) {
        this.config = config; this.repository = repository; this.clock = clock;
    }

    public boolean enabled() { return config.dmEnabled(); }

    public Optional<Set<FreebieSection>> current(String userId) { return repository.dmSubscribers().sections(userId); }

    /**
     * Edits an existing subscription, or holds a slot, sends the consent DM through {@code consent} (true when
     * Discord accepted it) and only then saves the subscription. A refused DM frees the slot again.
     */
    public Subscribed subscribe(String userId, Set<FreebieSection> sections, Predicate<MessageCreateData> consent) {
        if (sections.isEmpty()) throw new IllegalArgumentException("Pick at least one section.");
        var reserved = repository.dmSubscribers().reserve(userId, sections, config.dmMaxSubscribers(), clock.instant());
        switch (reserved) {
            case EDITED: return Subscribed.EDITED;
            case FULL: return Subscribed.FULL;
            case DISABLED: return Subscribed.DISABLED;
            case BUSY: return Subscribed.BUSY;
            default: break;
        }
        boolean delivered;
        try { delivered = consent.test(FreebieDm.consent(sections)); }
        catch (RuntimeException failure) { repository.dmSubscribers().release(userId); throw failure; }
        if (!delivered) { repository.dmSubscribers().release(userId); return Subscribed.DM_REFUSED; }
        // A slow consent DM may have outlived the hold; then the slot was freed and the user must try again.
        return repository.dmSubscribers().activate(userId, clock.instant()) ? Subscribed.SUBSCRIBED : Subscribed.BUSY;
    }

    /** Removes only {@code userId}'s own subscription and ends DMs still queued for them. */
    public boolean unsubscribe(String userId) {
        boolean removed = repository.dmSubscribers().unsubscribe(userId);
        repository.dmJobs().cancelForUser(userId, FreebieDm.Cancel.UNSUBSCRIBED, clock.instant());
        return removed;
    }
}
