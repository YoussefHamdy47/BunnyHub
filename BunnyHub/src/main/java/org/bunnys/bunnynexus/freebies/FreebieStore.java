package org.bunnys.bunnynexus.freebies;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Launchers a server can subscribe to. The persisted id is stable; never rename it.
 * Order matters: a giveaway listing several platforms maps to the first matching entry,
 * so PC storefronts win over the generic mobile/console tags GamerPower adds alongside them.
 */
public enum FreebieStore {
    EPIC("epic", "Epic Games Store", List.of("epic games store", "epic games")),
    STEAM("steam", "Steam", List.of("steam")),
    GOG("gog", "GOG", List.of("gog")),
    UBISOFT("ubisoft", "Ubisoft Connect", List.of("ubisoft connect", "ubisoft")),
    EA("ea", "EA app", List.of("ea app", "ea origin", "origin")),
    BATTLENET("battlenet", "Battle.net", List.of("battle.net", "battlenet")),
    ITCHIO("itchio", "itch.io", List.of("itch.io", "itchio")),
    DRM_FREE("drm-free", "DRM-Free / other PC", List.of("drm-free")),
    PLAYSTATION("playstation", "PlayStation", List.of("playstation 5", "playstation 4", "ps5", "ps4")),
    XBOX("xbox", "Xbox", List.of("xbox series x|s", "xbox one", "xbox 360", "xbox")),
    SWITCH("switch", "Nintendo Switch", List.of("nintendo switch", "switch")),
    ANDROID("android", "Android", List.of("android")),
    IOS("ios", "iOS", List.of("ios")),
    OTHER("other", "Other", List.of());

    private final String id;
    private final String label;
    private final List<String> platformTags;

    FreebieStore(String id, String label, List<String> platformTags) {
        this.id = id; this.label = label; this.platformTags = platformTags;
    }

    public String id() { return id; }
    public String label() { return label; }

    public static Optional<FreebieStore> byId(String id) {
        for (FreebieStore store : values()) if (store.id.equals(id)) return Optional.of(store);
        return Optional.empty();
    }

    /** Maps GamerPower's comma-separated platform text ("PC, Android, iOS, Epic Games Store"). */
    public static FreebieStore fromPlatforms(String platforms) {
        if (platforms == null) return OTHER;
        List<String> tags = java.util.Arrays.stream(platforms.split(","))
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT)).filter(tag -> !tag.isEmpty()).toList();
        for (FreebieStore store : values())
            for (String tag : tags) if (store.platformTags.contains(tag)) return store;
        return OTHER;
    }
}
