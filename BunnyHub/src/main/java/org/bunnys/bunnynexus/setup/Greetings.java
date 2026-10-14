package org.bunnys.bunnynexus.setup;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.bunnys.bunnynexus.setup.ServerSettings.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns a saved welcome or goodbye text into the message that gets posted, and checks that the bot can actually
 * post it and hand out the join roles. Pure apart from reading the guild it is given.
 */
public final class Greetings {
    private Greetings() {}

    public static final String PLACEHOLDERS = "{user} {name} {server} {count}";

    /** Who the message is about: the joining or leaving member. */
    public record Subject(String userId, String name) {
        public static Subject of(User user, Member member) {
            return new Subject(user.getId(), member != null ? member.getEffectiveName() : user.getEffectiveName());
        }
    }

    /** {@code {user}} mentions, {@code {name}} is the display name, {@code {server}} and {@code {count}} as read. */
    public static String render(String template, Subject subject, String serverName, int memberCount) {
        String text = template
                .replace("{user}", "<@" + subject.userId() + ">")
                .replace("{name}", MarkdownSanitizer.escape(subject.name(), true))
                .replace("{server}", MarkdownSanitizer.escape(serverName, true))
                .replace("{count}", String.valueOf(Math.max(memberCount, 0)));
        return text.length() <= Message.MAX_CONTENT_LENGTH ? text : text.substring(0, Message.MAX_CONTENT_LENGTH - 1) + "…";
    }

    /**
     * The message as posted. A welcome may ping the new member and nobody else; a goodbye pings nobody, so a custom
     * text can never be used to mass-ping roles or @everyone.
     */
    public static MessageCreateData message(Kind kind, String template, Subject subject, String serverName, int memberCount) {
        var builder = new MessageCreateBuilder().setContent(render(template, subject, serverName, memberCount))
                .setAllowedMentions(List.of());
        if (kind == Kind.WELCOME) builder.mentionUsers(subject.userId());
        return builder.build();
    }

    public static StandardGuildMessageChannel channel(Guild guild, String channelId) {
        return channelId == null ? null : guild.getChannelById(StandardGuildMessageChannel.class, channelId);
    }

    public static boolean canPost(Guild guild, StandardGuildMessageChannel channel) {
        return channel != null && guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
    }

    /** Why the bot can't give this role, or empty when it can. */
    public static Optional<String> roleProblem(Guild guild, Role role) {
        if (role.isPublicRole()) return Optional.of("@everyone can't be given out.");
        if (role.isManaged()) return Optional.of(role.getAsMention() + " belongs to an integration or bot, so it can't be given out.");
        Member self = guild.getSelfMember();
        if (!self.hasPermission(Permission.MANAGE_ROLES)) return Optional.of("I need the **Manage Roles** permission.");
        if (!self.canInteract(role))
            return Optional.of(role.getAsMention() + " is above my highest role. Drag my role above it in Server Settings → Roles.");
        return Optional.empty();
    }

    /** Saved join roles that still exist and that the bot can give. */
    public static List<Role> givableRoles(Guild guild, List<String> roleIds) {
        List<Role> roles = new ArrayList<>();
        for (Role role : roleIds.stream().map(guild::getRoleById).filter(Objects::nonNull).toList())
            if (roleProblem(guild, role).isEmpty()) roles.add(role);
        return roles;
    }

    /** Everything about these settings that won't work right now, one line each. */
    public static List<String> problems(Guild guild, ServerSettings settings) {
        List<String> problems = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            var greeting = settings.greeting(kind);
            if (!greeting.on()) continue;
            var channel = channel(guild, greeting.channelId());
            if (channel == null) problems.add(kind.label() + ": its channel was deleted. Pick a new one.");
            else if (!canPost(guild, channel)) problems.add(kind.label() + ": I can't post in " + channel.getAsMention()
                    + ". Give me **View Channel** and **Send Messages** there.");
        }
        for (String id : settings.joinRoles()) {
            Role role = guild.getRoleById(id);
            if (role == null) problems.add("Join roles: a role was deleted. Pick the roles again.");
            else roleProblem(guild, role).ifPresent(problem -> problems.add("Join roles: " + problem));
        }
        return problems.stream().distinct().toList();
    }
}
