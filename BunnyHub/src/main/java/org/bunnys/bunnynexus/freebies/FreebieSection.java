package org.bunnys.bunnynexus.freebies;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Groups launchers for owner review and for server settings. The major PC storefronts are the priority section:
 * their reviews ping the owners in the main review channel, everything else is reviewed quietly (optionally in a
 * second channel). Servers configure one channel, role and launcher set per section.
 *
 * <p>The code is a stable one-character id used inside component ids; never reuse or rename one.
 */
public enum FreebieSection {
    MAJOR_PC('m', "Major PC launchers", "🏆", "free-pc-games", "Free Games: Major PC"),
    OTHER_PC('i', "Indie & other PC", "🧩", "free-indie-games", "Free Games: Indie PC"),
    CONSOLE('c', "Console", "🎮", "free-console-games", "Free Games: Console"),
    MOBILE('p', "Mobile", "📱", "free-mobile-games", "Free Games: Mobile");

    private final char code;
    private final String label, emoji, channelName, roleName;

    FreebieSection(char code, String label, String emoji, String channelName, String roleName) {
        this.code = code; this.label = label; this.emoji = emoji; this.channelName = channelName; this.roleName = roleName;
    }

    public char code() { return code; }
    public String label() { return label; }
    public String emoji() { return emoji; }
    /** Channel and role names used by the dashboard's auto-setup. */
    public String channelName() { return channelName; }
    public String roleName() { return roleName; }
    public boolean priority() { return this == MAJOR_PC; }

    /** This section's launchers, in {@link FreebieStore} order. */
    public List<FreebieStore> stores() {
        return EnumSet.allOf(FreebieStore.class).stream().filter(store -> store.section() == this).toList();
    }

    public static Optional<FreebieSection> byCode(String code) {
        if (code == null || code.length() != 1) return Optional.empty();
        for (FreebieSection section : values()) if (section.code == code.charAt(0)) return Optional.of(section);
        return Optional.empty();
    }

    /** Encodes {@code sections} as their codes in declaration order ("mic"); the inverse of {@link #decode}. */
    public static String encode(Set<FreebieSection> sections) {
        var codes = new StringBuilder();
        for (FreebieSection section : values()) if (sections.contains(section)) codes.append(section.code);
        return codes.toString();
    }

    public static Set<FreebieSection> decode(String codes) {
        Set<FreebieSection> sections = EnumSet.noneOf(FreebieSection.class);
        if (codes != null) for (char c : codes.toCharArray()) byCode(String.valueOf(c)).ifPresent(sections::add);
        return sections;
    }
}
