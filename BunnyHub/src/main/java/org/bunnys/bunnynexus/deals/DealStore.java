package org.bunnys.bunnynexus.deals;

import org.bunnys.utils.AppDesign;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The stores the deals area reads, each from the store's own data (docs/deals/01-source.md), so every link goes
 * straight to the store. {@code id} is only the stored form in MongoDB.
 */
public enum DealStore {
    STEAM(1, "Steam", "steam", AppDesign.Emojis.STEAM),
    GOG(7, "GOG", "gog", AppDesign.Emojis.GOG);

    public final int id;
    public final String label, choice;
    /** The launcher's emoji ({@code <:name:id>}). Optional so a future store can start without one. */
    public final Optional<String> emoji;

    DealStore(int id, String label, String choice, String emoji) {
        this.id = id; this.label = label; this.choice = choice; this.emoji = Optional.ofNullable(emoji);
    }

    /** "<emoji> Steam", or just the name. */
    public String badge() { return emoji.map(e -> e + " " + label).orElse(label); }

    public static Optional<DealStore> byId(int id) {
        return Arrays.stream(values()).filter(store -> store.id == id).findFirst();
    }

    /** The {@code /deals store:} value (case and surrounding spaces ignored). */
    public static Optional<DealStore> byChoice(String choice) {
        if (choice == null) return Optional.empty();
        String clean = choice.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(store -> store.choice.equals(clean)).findFirst();
    }
}
