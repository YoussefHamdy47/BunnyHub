package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bunnys.bunnynexus.commands.freebies.FreebieSettingsCommands.Reply;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.utils.Embeds;
import java.math.BigInteger;
import java.util.*;
import java.util.stream.Stream;

/**
 * Before auto-setup creates anything, looks for channels and roles the server already made for free games
 * ({@code #epic-games}, {@code @Steam}, {@code #freebies}, ...) and asks the admin which of them to use.
 *
 * <p>The scan is a pure read of the guild cache and is deterministic, so the admin's choice travels in component
 * ids as a bit mask over the scan result plus a short fingerprint of it. If the server changed in between (a channel
 * renamed or deleted), the fingerprint no longer matches and the admin is asked again instead of guessing.
 */
public final class FreebieExistingSetup {
    private FreebieExistingSetup() {}

    /** A channel or role that looks like it was made for one launcher, or (store empty) for free games in general. */
    public record Found(boolean channel, Optional<FreebieStore> store, String id, String name) {
        String describe() {
            String scope = store.map(FreebieStore::label).orElse("all chosen sections");
            return channel ? "Post " + scope + " in #" + name : "Ping @" + name + " for " + scope;
        }
    }

    /** One entry in a Discord select menu each; the menu holds at most 25. */
    static final int MAX_FOUND = 25;
    private static final Set<String> GENERIC = Set.of("freegames", "freegame", "freebies", "freebie", "giveaways", "gamegiveaways",
            "freestuff", "gamedeals");
    private static final Map<FreebieStore, List<String>> KEYWORDS = new EnumMap<>(Map.ofEntries(
            Map.entry(FreebieStore.EPIC, List.of("epic", "epicgames", "egs")),
            Map.entry(FreebieStore.STEAM, List.of("steam")),
            Map.entry(FreebieStore.GOG, List.of("gog")),
            Map.entry(FreebieStore.UBISOFT, List.of("ubisoft", "uplay", "ubi")),
            Map.entry(FreebieStore.EA, List.of("ea", "eaapp", "eagames", "origin")),
            Map.entry(FreebieStore.BATTLENET, List.of("battlenet", "blizzard", "bnet")),
            Map.entry(FreebieStore.ITCHIO, List.of("itch", "itchio")),
            Map.entry(FreebieStore.DRM_FREE, List.of("drmfree")),
            Map.entry(FreebieStore.PLAYSTATION, List.of("playstation", "ps4", "ps5", "psn", "psplus")),
            Map.entry(FreebieStore.XBOX, List.of("xbox", "gamepass")),
            Map.entry(FreebieStore.SWITCH, List.of("switch", "nintendo")),
            Map.entry(FreebieStore.ANDROID, List.of("android", "googleplay")),
            Map.entry(FreebieStore.IOS, List.of("ios", "iphone", "appstore"))));

    /**
     * Launcher match by whole words, so {@code #steam-deals} and {@code @Epic Games} match but {@code #teams} does
     * not. A name naming two launchers is ambiguous and skipped; the admin can still pick it in the section form.
     */
    static Optional<FreebieStore> launcher(String name) {
        var words = words(name);
        String joined = String.join("", words);
        FreebieStore match = null;
        for (var entry : KEYWORDS.entrySet())
            for (String keyword : entry.getValue())
                if (words.contains(keyword) || (keyword.length() > 4 && joined.contains(keyword))) {
                    if (match != null && match != entry.getKey()) return Optional.empty();
                    match = entry.getKey();
                }
        return Optional.ofNullable(match);
    }

    static boolean generic(String name) {
        var words = words(name);
        String joined = String.join("", words);
        return GENERIC.stream().anyMatch(joined::contains);
    }

    private static List<String> words(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).filter(w -> !w.isEmpty()).toList();
    }

    /**
     * Candidates for the chosen sections, at most one channel and one role per launcher (and one general channel
     * and role), in a stable order. What auto-setup already reuses by its own names is not offered again.
     */
    public static List<Found> scan(Guild guild, Set<FreebieSection> sections, boolean channelPerSection) {
        Set<String> ownChannels = new HashSet<>(Set.of(FreebieServerSetup.SHARED_CHANNEL));
        Set<String> ownRoles = new HashSet<>();
        for (FreebieSection section : FreebieSection.values()) { ownChannels.add(section.channelName()); ownRoles.add(section.roleName()); }
        List<StandardGuildMessageChannel> channels = Stream.<StandardGuildMessageChannel>concat(guild.getTextChannels().stream(),
                        guild.getNewsChannels().stream())
                .filter(c -> !(ownChannels.contains(c.getName()) && c.getParentCategory() != null
                        && c.getParentCategory().getName().equalsIgnoreCase(FreebieServerSetup.CATEGORY_NAME)))
                .sorted(Comparator.comparing(StandardGuildMessageChannel::getName).thenComparing(StandardGuildMessageChannel::getId)).toList();
        List<Role> roles = guild.getRoles().stream().filter(r -> !r.isPublicRole() && !r.isManaged() && !ownRoles.contains(r.getName()))
                .sorted(Comparator.comparing(Role::getName).thenComparing(Role::getId)).toList();
        Map<String, Found> picked = new LinkedHashMap<>();
        for (FreebieStore store : FreebieStore.values()) {
            if (!sections.contains(store.section())) continue;
            channels.stream().filter(c -> launcher(c.getName()).filter(store::equals).isPresent()).findFirst()
                    .ifPresent(c -> picked.putIfAbsent("c" + store.id(), new Found(true, Optional.of(store), c.getId(), c.getName())));
            roles.stream().filter(r -> launcher(r.getName()).filter(store::equals).isPresent()).findFirst()
                    .ifPresent(r -> picked.putIfAbsent("r" + store.id(), new Found(false, Optional.of(store), r.getId(), r.getName())));
        }
        if (!channelPerSection)
            channels.stream().filter(c -> launcher(c.getName()).isEmpty() && generic(c.getName())).findFirst()
                    .ifPresent(c -> picked.put("c*", new Found(true, Optional.empty(), c.getId(), c.getName())));
        roles.stream().filter(r -> launcher(r.getName()).isEmpty() && generic(r.getName())).findFirst()
                .ifPresent(r -> picked.put("r*", new Found(false, Optional.empty(), r.getId(), r.getName())));
        return picked.values().stream().limit(MAX_FOUND).toList();
    }

    /** Short fingerprint of a scan; a choice made against another scan is never applied. */
    public static String fingerprint(List<Found> found) {
        var text = new StringBuilder();
        for (Found f : found) text.append(f.channel() ? 'c' : 'r').append(f.store().map(FreebieStore::id).orElse("*")).append(f.id()).append(';');
        return Integer.toString(text.toString().hashCode() & 0x7fffffff, 36);
    }

    public static String encode(Set<Integer> chosen) {
        BigInteger mask = BigInteger.ZERO;
        for (int index : chosen) mask = mask.setBit(index);
        return mask.toString(36);
    }

    /** Empty when the mask is malformed. */
    public static Optional<Set<Integer>> decode(String mask) {
        try {
            var bits = new BigInteger(mask, 36);
            if (bits.signum() < 0 || bits.bitLength() > MAX_FOUND) return Optional.empty();
            Set<Integer> chosen = new TreeSet<>();
            for (int i = 0; i < MAX_FOUND; i++) if (bits.testBit(i)) chosen.add(i);
            return Optional.of(chosen);
        } catch (NumberFormatException malformed) { return Optional.empty(); }
    }

    /** The admin's choice ({@code <mask>.<fingerprint>}), only when it was made against this exact scan. */
    public static Optional<Set<Integer>> answer(String arg, List<Found> found) {
        int dot = arg.indexOf('.');
        if (dot < 0 || !arg.substring(dot + 1).equals(fingerprint(found))) return Optional.empty();
        return decode(arg.substring(0, dot)).filter(chosen -> chosen.stream().allMatch(i -> i < found.size()));
    }

    public static List<Found> select(List<Found> found, Set<Integer> chosen) {
        List<Found> result = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) if (chosen.contains(i)) result.add(found.get(i));
        return result;
    }

    /**
     * The question: which of these should auto-setup use? Run buttons carry {@code <mask>.<fingerprint>}; the
     * select redraws this view ({@code freebie_autosel:<guild>:<layout>:<sections>:<fingerprint>}).
     */
    public static Reply prompt(String guildId, Set<FreebieSection> sections, String layout, List<Found> found,
                               Set<Integer> chosen, String notice) {
        return prompt(guildId, sections, layout, found, chosen, notice, FreebieCatchUp.AUTO);
    }

    /** As above, keeping auto-setup's "post current games now" choice on every control. */
    public static Reply prompt(String guildId, Set<FreebieSection> sections, String layout, List<Found> found,
                               Set<Integer> chosen, String notice, FreebieCatchUp post) {
        String codes = FreebieCatchUp.token(sections, post), print = fingerprint(found);
        var lines = new StringBuilder();
        for (int i = 0; i < found.size(); i++)
            lines.append(chosen.contains(i) ? "☑️ " : "⬜ ").append(MarkdownSanitizer.escape(found.get(i).describe())).append('\n');
        var embed = Embeds.of("🔎", "You already have some free-game channels or roles").setTimestamp(null)
                .setDescription((notice == null ? "" : notice + "\n\n")
                        + "Before I create anything: these look like they were made for free games. Pick the ones I should use; "
                        + "I'll create the rest.\n\n" + lines.toString().strip())
                .addField("GOOD TO KNOW", "Existing channels keep their own permissions; I only check that I can post there. "
                        + "A role is only offered in the role picker if it grants nothing beyond @everyone.", false);
        var menu = StringSelectMenu.create(FreebieDashboard.AUTO_SELECT_PREFIX + ":" + guildId + ":" + layout + ":" + codes + ":" + print)
                .setPlaceholder("Existing channels and roles to use").setRequiredRange(0, found.size());
        for (int i = 0; i < found.size(); i++) {
            var f = found.get(i);
            menu.addOption(label(f.describe()), String.valueOf(i),
                    Emoji.fromUnicode(f.channel() ? "#️⃣" : "🔔"));
        }
        menu.setDefaultValues(chosen.stream().map(String::valueOf).toList());
        String run = FreebieDashboard.RUN_PREFIX + ":" + layout + ":" + guildId + ":" + codes + ":";
        return new Reply(embed.build(), List.of(ActionRow.of(menu.build()), ActionRow.of(
                Button.success(run + encode(chosen) + "." + print, chosen.isEmpty() ? "Create everything new" : "Use selected and set up"),
                Button.secondary(run + "0." + print, "Ignore them, create new"),
                Button.secondary(FreebieDashboard.NAV_PREFIX + ":" + FreebieDashboard.AUTO + ":" + guildId, "Back"))));
    }

    private static String label(String text) { return text.length() <= 100 ? text : text.substring(0, 99) + "…"; }

    /** All found items ticked: the default answer when the question is first asked. */
    public static Set<Integer> all(List<Found> found) {
        Set<Integer> chosen = new TreeSet<>();
        for (int i = 0; i < found.size(); i++) chosen.add(i);
        return chosen;
    }
}
