package org.bunnys.bunnynexus.freebies;

import net.dv8tion.jda.api.components.container.Container;
import org.bunnys.utils.BunnyLog;

import java.time.Instant;
import java.util.Optional;

/**
 * The ad card under a free-game alert (docs/product-updates/README.md, "Ads in alerts"). The product-update system
 * picks it per server; until it registers, and whenever it fails, alerts carry no ad.
 */
public final class FreebiePromos {
    private FreebiePromos() {}

    /** The ad card for this server now (already counted as shown), or nothing. Blocking; called on the sending worker. */
    public interface Source { Optional<Container> cardFor(String guildId, Instant now, java.util.List<FreebieOffer> page); }

    private static final Source NONE = (guild, now, page) -> Optional.empty();
    private static volatile Source source = NONE;

    public static void use(Source ads) { source = ads == null ? NONE : ads; }

    static Optional<Container> cardFor(String guildId, Instant now) { return cardFor(guildId, now, java.util.List.of()); }

    /** For an alert carrying these games, which a live ad may talk about. */
    static Optional<Container> cardFor(String guildId, Instant now, java.util.List<FreebieOffer> page) {
        try { return source.cardFor(guildId, now, page); }
        catch (RuntimeException failure) {
            BunnyLog.warning("[Freebies] Ad skipped for " + guildId + ": " + failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
