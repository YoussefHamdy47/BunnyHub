package org.bunnys.bunnynexus.updates;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The features servers can hear about (docs/product-updates/README.md). Fixed, reviewed wording; newest last.
 * {@link #usedBy} names the server state that means a server already has the feature, so it is not told about it.
 */
public enum ProductUpdate {
    DEALS_BOARD("deals-board", "New: a live game Deals Board",
            "One message with the best current deals on PC and console: 75% off or more on well-reviewed games, cheapest "
                    + "first, kept up to date. It can ping a role when new deals arrive, at most once every 6, 12 or 24 hours.",
            "Run `/free-games setup` and press **Create Deals Board**.",
            Feature.DEALS_BOARD);

    /** Server state an update can check. */
    public enum Feature { DEALS_BOARD }

    public static final int MAX_PITCH = 400;

    private final String id, title, pitch, howTo;
    private final Feature usedBy;

    ProductUpdate(String id, String title, String pitch, String howTo, Feature usedBy) {
        if (pitch.length() > MAX_PITCH) throw new IllegalArgumentException("pitch too long: " + id);
        this.id = id; this.title = title; this.pitch = pitch; this.howTo = howTo; this.usedBy = usedBy;
    }


    public String id() { return id; }
    public String title() { return title; }
    public String pitch() { return pitch; }
    public String howTo() { return howTo; }
    public Feature usedBy() { return usedBy; }

    public boolean usedBy(Set<Feature> features) { return features.contains(usedBy); }

    public static Optional<ProductUpdate> byId(String id) {
        return Arrays.stream(values()).filter(u -> u.id.equals(id)).findFirst();
    }
}
