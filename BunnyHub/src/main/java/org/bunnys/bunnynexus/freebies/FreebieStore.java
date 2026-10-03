package org.bunnys.bunnynexus.freebies;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Arrays;

/**
 * Launchers a server can subscribe to. The persisted id is stable; never rename it.
 * Order matters: a giveaway listing several platforms maps to the first matching entry,
 * so PC storefronts win over the generic mobile/console tags GamerPower adds alongside them.
 */
public enum FreebieStore {
    EPIC("epic", "Epic Games Store", List.of("epic games store", "epic games"), FreebieSection.MAJOR_PC),
    STEAM("steam", "Steam", List.of("steam"), FreebieSection.MAJOR_PC),
    GOG("gog", "GOG", List.of("gog"), FreebieSection.MAJOR_PC),
    UBISOFT("ubisoft", "Ubisoft Connect", List.of("ubisoft connect", "ubisoft"), FreebieSection.MAJOR_PC),
    EA("ea", "EA app", List.of("ea app", "ea origin", "origin"), FreebieSection.MAJOR_PC),
    BATTLENET("battlenet", "Battle.net", List.of("battle.net", "battlenet"), FreebieSection.MAJOR_PC),
    ITCHIO("itchio", "itch.io", List.of("itch.io", "itchio"), FreebieSection.OTHER_PC),
    DRM_FREE("drm-free", "DRM-Free / other PC", List.of("drm-free"), FreebieSection.OTHER_PC),
    PLAYSTATION("playstation", "PlayStation", List.of("playstation 5", "playstation 4", "ps5", "ps4"), FreebieSection.CONSOLE),
    XBOX("xbox", "Xbox", List.of("xbox series x|s", "xbox one", "xbox 360", "xbox"), FreebieSection.CONSOLE),
    SWITCH("switch", "Nintendo Switch", List.of("nintendo switch", "switch"), FreebieSection.CONSOLE),
    ANDROID("android", "Android", List.of("android"), FreebieSection.MOBILE),
    IOS("ios", "iOS", List.of("ios"), FreebieSection.MOBILE),
    OTHER("other", "Other", List.of(), FreebieSection.OTHER_PC);

    private final String id;
    private final String label;
    private final List<String> platformTags;
    private final FreebieSection section;

    FreebieStore(String id, String label, List<String> platformTags, FreebieSection section) {
        this.id = id; this.label = label; this.platformTags = platformTags; this.section = section;
    }

    public String id() { return id; }
    public String label() { return label; }
    public FreebieSection section() { return section; }

    public static Optional<FreebieStore> byId(String id) {
        for (FreebieStore store : values()) if (store.id.equals(id)) return Optional.of(store);
        return Optional.empty();
    }

    /** Maps GamerPower's comma-separated platform text ("PC, Android, iOS, Epic Games Store"). */
    public static FreebieStore fromPlatforms(String platforms) {
        if (platforms == null) return OTHER;
        List<String> tags = Arrays.stream(platforms.split(","))
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT)).filter(tag -> !tag.isEmpty()).toList();
        for (FreebieStore store : values())
            for (String tag : tags) if (store.platformTags.contains(tag)) return store;
        return OTHER;
    }
}
