package org.bunnys.bunnynexus.deals;

import org.bunnys.utils.AppDesign;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The stores the deals area reads, each from the store's own data (docs/deals/01-source.md), so every link goes
 * straight to the store. {@code id} is only the stored form in MongoDB.
 *
 * <p>{@link #stars}: the store rates in stars out of 5, kept in tenths (47 = 4.7); otherwise ratings are Steam's
 * percent positive. Nintendo's eShop has no player ratings, so its deals carry the Steam rating of the same game.
 */
public enum DealStore {
    STEAM(1, "Steam", "steam", AppDesign.Emojis.STEAM, false),
    GOG(7, "GOG", "gog", AppDesign.Emojis.GOG, true),
    PLAYSTATION(2, "PlayStation", "playstation", AppDesign.Emojis.PLAYSTATION, true),
    XBOX(3, "Xbox", "xbox", AppDesign.Emojis.XBOX, true),
    NINTENDO(4, "Nintendo Switch", "nintendo", AppDesign.Emojis.SWITCH, false);

    public final int id;
    public final String label, choice;
    /** The launcher's emoji ({@code <:name:id>}). Optional so a future store can start without one. */
    public final Optional<String> emoji;
    public final boolean stars;

    DealStore(int id, String label, String choice, String emoji, boolean stars) {
        this.id = id; this.label = label; this.choice = choice; this.emoji = Optional.ofNullable(emoji); this.stars = stars;
    }

    /** "<emoji> Steam", or just the name. */
    public String badge() { return emoji.map(e -> e + " " + label).orElse(label); }

    /** The consoles, whose prices are their own US stores' and whose ratings are their players' (Nintendo: Steam's). */
    public boolean console() { return this == PLAYSTATION || this == XBOX || this == NINTENDO; }

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
