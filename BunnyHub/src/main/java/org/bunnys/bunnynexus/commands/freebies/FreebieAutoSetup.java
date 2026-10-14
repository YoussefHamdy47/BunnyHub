package org.bunnys.bunnynexus.commands.freebies;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.bunnys.bunnynexus.commands.freebies.FreebieExistingSetup.Found;
import org.bunnys.bunnynexus.freebies.FreebieSection;
import org.bunnys.bunnynexus.freebies.FreebieStore;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions;
import org.bunnys.bunnynexus.freebies.FreebieSystem;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.SaveResult;
import org.bunnys.bunnynexus.freebies.FreebieSubscriptions.Subscription;
import java.time.Instant;
import java.util.*;

/**
 * Auto-setup: creates (or reuses by name) the category, alert channels and one ping role per section, or uses the
 * existing channels and roles the admin picked from {@link FreebieExistingSetup}, saves every chosen section with
 * all of its launchers, then posts the role picker. Blocking Discord calls; command workers only, after the
 * interaction was acknowledged.
 */
public final class FreebieAutoSetup {
    private FreebieAutoSetup() {}

    private static final EnumSet<Permission> BOT_CHANNEL_ACCESS = EnumSet.of(Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS, Permission.MESSAGE_HISTORY);

    /** The admin's answer, resolved against the guild: launchers moved to existing channels and existing roles. */
    record Reuse(Map<FreebieStore, StandardGuildMessageChannel> channels, Map<FreebieStore, Role> roles,
                 Optional<StandardGuildMessageChannel> shared, Optional<Role> sharedRole) {
        static Reuse of(Guild guild, List<Found> picked) {
            Map<FreebieStore, StandardGuildMessageChannel> channels = new EnumMap<>(FreebieStore.class);
            Map<FreebieStore, Role> roles = new EnumMap<>(FreebieStore.class);
            StandardGuildMessageChannel shared = null;
            Role sharedRole = null;
            for (Found f : picked) {
                if (f.channel()) {
                    var channel = guild.getChannelById(StandardGuildMessageChannel.class, f.id());
                    if (channel == null) continue;
                    if (f.store().isPresent()) channels.put(f.store().get(), channel); else shared = channel;
                } else {
                    var role = guild.getRoleById(f.id());
                    if (role == null) continue;
                    if (f.store().isPresent()) roles.put(f.store().get(), role); else sharedRole = role;
                }
            }
            return new Reuse(channels, roles, Optional.ofNullable(shared), Optional.ofNullable(sharedRole));
        }

        /** The section's launchers that still need the section's own channel. */
        List<FreebieStore> remaining(FreebieSection section) {
            return section.stores().stream().filter(s -> !channels.containsKey(s)).toList();
        }
    }

    public static String run(Guild guild, Member actor, Set<FreebieSection> sections, boolean channelPerSection,
                             FreebieSubscriptions subscriptions, List<Found> picked) {
        return run(guild, actor, sections, channelPerSection, subscriptions, picked, FreebieCatchUp.OFF, null);
    }

    /** As above; {@code post} decides per saved channel whether the games free right now are posted at once. */
    public static String run(Guild guild, Member actor, Set<FreebieSection> sections, boolean channelPerSection,
                             FreebieSubscriptions subscriptions, List<Found> picked, FreebieCatchUp post, FreebieSystem system) {
        if (sections.isEmpty()) return "⚠️ Pick at least one section.";
        if (!actor.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL))
            return "⚠️ Auto-setup creates channels and roles, so you need **Manage Roles** and **Manage Channels**.";
        var self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES, Permission.MANAGE_CHANNEL))
            return "⚠️ I need **Manage Roles** and **Manage Channels** in Server Settings → Roles to set this up.";
        if (!self.hasPermission(BOT_CHANNEL_ACCESS))
            return "⚠️ I need **View Channels**, **Send Messages**, **Embed Links** and **Read Message History** server-wide "
                    + "to create alert channels I can post in.";
        var reuse = Reuse.of(guild, picked);
        // Refuse before creating anything, so a full server is not left with channels that receive nothing.
        Set<String> reused = new HashSet<>();
        reuse.channels().forEach((store, channel) -> { if (sections.contains(store.section())) reused.add(channel.getId()); });
        boolean anyRemaining = sections.stream().anyMatch(s -> !reuse.remaining(s).isEmpty());
        int created = channelPerSection ? (int) sections.stream().filter(s -> !reuse.remaining(s).isEmpty()).count()
                : anyRemaining && reuse.shared().isEmpty() ? 1 : 0;
        if (!channelPerSection && anyRemaining) reuse.shared().ifPresent(c -> reused.add(c.getId()));
        long needed = channelsAfterAutoSetup(subscriptions.forGuild(guild.getId()), sections, reused, created);
        if (needed > FreebieSubscriptions.MAX_CHANNELS_PER_GUILD)
            return "⚠️ That would use " + needed + " alert channels; a server can have " + FreebieSubscriptions.MAX_CHANNELS_PER_GUILD
                    + ". Choose **One channel for everything**, use fewer existing channels or sections, or turn another section off first.";
        try {
            var before = subscriptions.forGuild(guild.getId());
            var placed = new LinkedHashMap<String, Set<FreebieStore>>();
            String notice = save(guild, actor, sections, channelPerSection, subscriptions, reuse, placed);
            return notice + catchUp(guild, before, placed, post, system);
        } catch (InsufficientPermissionException missing) {
            return "⚠️ I'm missing **" + missing.getPermission().getName() + "** to finish. Anything created so far is kept; run it again after fixing it.";
        } catch (ErrorResponseException refused) {
            if (refused.getErrorResponse() != ErrorResponse.MISSING_PERMISSIONS && refused.getErrorResponse() != ErrorResponse.MISSING_ACCESS)
                throw refused;
            return "⚠️ Discord refused because of my permissions (is my role high enough?). Anything created so far is kept; run it again after fixing it.";
        }
    }

    /**
     * Posts current games in each channel auto-setup saved, when the choice says so for that channel: a channel is
     * new for a launcher's section when it had none of that section's launchers before.
     */
    static String catchUp(Guild guild, List<Subscription> before, Map<String, Set<FreebieStore>> placed, FreebieCatchUp post,
                          FreebieSystem system) {
        if (system == null || placed.isEmpty()) return "";
        int live = 0, queued = 0;
        boolean any = false;
        for (var entry : placed.entrySet()) {
            String channel = entry.getKey();
            Set<FreebieStore> wanted = EnumSet.noneOf(FreebieStore.class);
            for (FreebieStore store : entry.getValue()) {
                boolean fresh = before.stream().noneMatch(s -> s.channelId().equals(channel) && s.store().section() == store.section());
                if (post.resolve(fresh)) wanted.add(store);
            }
            if (wanted.isEmpty()) continue;
            any = true;
            var result = system.catchUpChannel(guild.getId(), channel, wanted::contains);
            live += result.live(); queued += result.queued();
        }
        return any ? "\n" + FreebieCatchUp.reply(new FreebieSystem.CatchUp(live, queued), "the new channels") : "";
    }

    private static String save(Guild guild, Member actor, Set<FreebieSection> sections, boolean channelPerSection,
                               FreebieSubscriptions subscriptions, Reuse reuse, Map<String, Set<FreebieStore>> placed) {
        Instant now = Instant.now();
        Category[] category = new Category[1];
        StandardGuildMessageChannel shared = null;
        boolean anyRemaining = sections.stream().anyMatch(s -> !reuse.remaining(s).isEmpty());
        if (!channelPerSection && anyRemaining)
            shared = reuse.shared().orElseGet(() -> channel(guild, category(guild, category), FreebieServerSetup.SHARED_CHANNEL));
        List<String> problems = new ArrayList<>();
        Set<StandardGuildMessageChannel> used = new LinkedHashSet<>();
        Map<Role, Set<FreebieStore>> pings = new LinkedHashMap<>();
        for (FreebieSection section : sections) {
            var remaining = reuse.remaining(section);
            boolean needsSectionRole = reuse.sharedRole().isEmpty()
                    && section.stores().stream().anyMatch(s -> !reuse.roles().containsKey(s));
            Role sectionRole = reuse.sharedRole().orElseGet(() -> needsSectionRole ? role(guild, section.roleName()) : null);
            if (remaining.isEmpty()) subscriptions.clearSection(guild.getId(), section);
            else {
                var channel = shared != null ? shared : channel(guild, category(guild, category), section.channelName());
                // A reused channel or role may have been changed since an earlier run; check it like a manual setup.
                var problem = FreebieSettingsCommands.validate(guild, channel, Optional.ofNullable(sectionRole));
                if (problem.isPresent()) { problems.add(section.label() + ": " + problem.get()); continue; }
                if (subscriptions.setSection(guild.getId(), section, channel.getId(), EnumSet.copyOf(remaining),
                        Optional.ofNullable(sectionRole).map(Role::getId), actor.getId(), now) == SaveResult.LIMIT_REACHED) {
                    problems.add(section.label() + ": channel limit reached");
                    continue;
                }
                used.add(channel);
                placed.computeIfAbsent(channel.getId(), k -> EnumSet.noneOf(FreebieStore.class)).addAll(remaining);
                for (FreebieStore store : remaining) {
                    Role ping = reuse.roles().getOrDefault(store, sectionRole);
                    if (ping != sectionRole) launcher(guild, actor, subscriptions, channel, store, ping, problems, now);
                    if (ping != null) pings.computeIfAbsent(ping, r -> EnumSet.noneOf(FreebieStore.class)).add(store);
                }
            }
            for (FreebieStore store : section.stores()) {
                var existing = reuse.channels().get(store);
                if (existing == null) continue;
                Role ping = reuse.roles().getOrDefault(store, sectionRole);
                if (launcher(guild, actor, subscriptions, existing, store, ping, problems, now)) {
                    used.add(existing);
                    placed.computeIfAbsent(existing.getId(), k -> EnumSet.noneOf(FreebieStore.class)).add(store);
                    if (ping != null) pings.computeIfAbsent(ping, r -> EnumSet.noneOf(FreebieStore.class)).add(store);
                }
            }
        }
        if (used.isEmpty()) return "⚠️ Auto-setup saved nothing:\n• " + String.join("\n• ", problems);

        var notice = new StringBuilder("✅ Auto-setup done. Alerts post in ")
                .append(String.join(", ", used.stream().map(StandardGuildMessageChannel::getAsMention).toList())).append('.');
        var pickable = new LinkedHashMap<Role, Set<FreebieStore>>();
        pings.forEach((role, stores) -> { if (FreebieServerSetup.selfAssignable(role, guild.getSelfMember())) pickable.put(role, stores); });
        if (!pings.isEmpty())
            notice.append("\nPings: ").append(String.join(", ", pings.keySet().stream().map(Role::getAsMention).toList())).append('.');
        if (!pickable.isEmpty()) {
            var panelChannel = used.iterator().next();
            panelChannel.sendMessage(FreebieServerSetup.rolePanel(pickable)).complete();
            notice.append("\nMembers can pick their pings from the role picker in ").append(panelChannel.getAsMention()).append('.');
        }
        if (pickable.size() < pings.size())
            notice.append("\nℹ️ Roles that grant permissions or unlock channels are not offered in the role picker.");
        if (!problems.isEmpty()) notice.append("\n⚠️ Not saved:\n• ").append(String.join("\n• ", problems));
        return notice.toString();
    }

    /** One launcher in one channel with its own ping; reports why when it cannot be saved. */
    private static boolean launcher(Guild guild, Member actor, FreebieSubscriptions subscriptions, StandardGuildMessageChannel channel,
                                    FreebieStore store, Role ping, List<String> problems, Instant now) {
        var problem = FreebieSettingsCommands.validate(guild, channel, Optional.ofNullable(ping));
        if (problem.isPresent()) { problems.add(store.label() + ": " + problem.get()); return false; }
        var result = subscriptions.save(new Subscription(guild.getId(), channel.getId(), store,
                Optional.ofNullable(ping).map(Role::getId)), actor.getId(), now);
        if (result == SaveResult.LIMIT_REACHED) { problems.add(store.label() + ": channel limit reached"); return false; }
        return true;
    }

    /**
     * Alert channels in use once auto-setup has run: those still used by sections it leaves alone, the existing
     * channels it reuses, plus the ones it creates. Conservative: a channel shared with another section may count twice.
     */
    static long channelsAfterAutoSetup(List<Subscription> saved, Set<FreebieSection> sections, Set<String> reused, int created) {
        Set<String> channels = new HashSet<>(reused);
        saved.stream().filter(s -> !sections.contains(s.store().section())).map(Subscription::channelId).forEach(channels::add);
        return channels.size() + created;
    }

    static long channelsAfterAutoSetup(List<Subscription> saved, Set<FreebieSection> sections, boolean channelPerSection) {
        return channelsAfterAutoSetup(saved, sections, Set.of(), channelPerSection ? sections.size() : 1);
    }

    private static Category category(Guild guild, Category[] cached) {
        if (cached[0] != null) return cached[0];
        var existing = guild.getCategoriesByName(FreebieServerSetup.CATEGORY_NAME, true);
        cached[0] = existing.isEmpty() ? guild.createCategory(FreebieServerSetup.CATEGORY_NAME).complete() : existing.getFirst();
        return cached[0];
    }

    /** Alert channels are read-only for members; the bot gets exactly what delivery needs. */
    private static TextChannel channel(Guild guild, Category category, String name) {
        for (TextChannel channel : category.getTextChannels()) if (channel.getName().equals(name)) return channel;
        return category.createTextChannel(name).setTopic("Free games, checked by the bot owner before they are posted.")
                .addPermissionOverride(guild.getPublicRole(), EnumSet.noneOf(Permission.class), EnumSet.of(Permission.MESSAGE_SEND))
                .addMemberPermissionOverride(guild.getSelfMember().getIdLong(), BOT_CHANNEL_ACCESS, EnumSet.noneOf(Permission.class))
                .complete();
    }

    /** Reuses a same-named role only when members could safely hand it to themselves. */
    private static Role role(Guild guild, String name) {
        for (Role role : guild.getRolesByName(name, false)) if (FreebieServerSetup.selfAssignable(role, guild.getSelfMember())) return role;
        return guild.createRole().setName(name).setMentionable(true).setPermissions(0L).complete();
    }
}
