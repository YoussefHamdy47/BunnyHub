package org.bunnys.bunnynexus.info;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * The per-member details on a {@code /info user} card: badges, display colour, roles and the permissions worth
 * naming. Each helper returns null (or an empty list) when there is nothing to show, so {@link InfoEmbeds} can
 * leave the field out instead of printing "None".
 */
final class MemberDetails {
    private MemberDetails() {}

    /**
     * Permissions worth naming, in descending order of consequence.
     *
     * <p>Not every permission a member holds - that list is long, mostly uninteresting,
     * and would bury the two or three that actually matter. These are the ones that
     * change what somebody can do to other people.
     */
    static final Permission[] KEY_PERMISSIONS = {
            Permission.ADMINISTRATOR,
            Permission.MANAGE_SERVER,
            Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL,
            Permission.MANAGE_WEBHOOKS,
            Permission.MANAGE_PERMISSIONS,
            Permission.BAN_MEMBERS,
            Permission.KICK_MEMBERS,
            Permission.MODERATE_MEMBERS,
            Permission.MESSAGE_MANAGE,
            Permission.MANAGE_THREADS,
            Permission.NICKNAME_MANAGE,
            Permission.MANAGE_GUILD_EXPRESSIONS,
            Permission.MESSAGE_MENTION_EVERYONE,
            Permission.VIEW_AUDIT_LOGS,
            Permission.MESSAGE_TTS,
            Permission.VOICE_MUTE_OTHERS,
            Permission.VOICE_DEAF_OTHERS,
            Permission.VOICE_MOVE_OTHERS
    };

    /**
     * Discord's account badges, by name.
     *
     * <p>Named rather than drawn: the badge artwork is Discord's, and this bot's emoji
     * uses standard emoji. Inventing approximations would look worse than reading them.
     *
     * @return null when the account carries none, so the caller can drop the field
     */
    static String badges(User target) {
        EnumSet<User.UserFlag> flags = target.getFlags();
        if (flags.isEmpty())
            return null;

        List<String> named = new ArrayList<>();
        for (User.UserFlag flag : flags) {
            // Internal plumbing rather than anything a person earned or displays.
            if (flag == User.UserFlag.UNKNOWN
                    || flag == User.UserFlag.TEAM_USER
                    || flag == User.UserFlag.BOT_HTTP_INTERACTIONS)
                continue;
            named.add(flag.getName());
        }

        return named.isEmpty() ? null : String.join(", ", named);
    }

    /**
     * The member's display colour, linked to a full swatch.
     *
     * <p>Read through {@code getColors()} rather than the deprecated {@code getColor()},
     * because Discord roles are no longer a single colour - they can be a two-stop
     * gradient or holographic. Each is named for what it is instead of being flattened
     * to its first stop and quietly mislabelled.
     *
     * <p>The embed itself stays {@code ColorCodes.DEFAULT}. Recolouring the whole card
     * per member would break the one-accent rule the rest of the bot follows, and a
     * profile is not the place to start making exceptions to it.
     *
     * @return null when the member has no colour of their own
     */
    static String colourField(Member member) {
        var colours = member.getColors();
        if (colours == null || colours.isDefault())
            return null;

        if (colours.isHolographic())
            return "🎨" + " Holographic";

        String primary = swatch(colours.getPrimary());
        if (primary == null)
            return null;

        if (colours.isGradient()) {
            String secondary = swatch(colours.getSecondary());
            return "🎨" + " " + primary + " to " + (secondary == null ? "?" : secondary);
        }

        return "🎨" + " " + primary;
    }

    /** A hex code linked to a full-size swatch, or null when there is no colour. */
    static String swatch(Color colour) {
        if (colour == null)
            return null;

        String hex = String.format("%06X", colour.getRGB() & 0xFFFFFF);
        return "[#" + hex + "](https://www.color-hex.com/color/" + hex + ")";
    }

    static String highestRole(Member member) {
        List<Role> roles = member.getRoles();
        return roles.isEmpty() ? "@everyone" : roles.get(0).getAsMention();
    }

    /**
     * Every role, highest first, trimmed to fit.
     *
     * <p>{@code getRoles()} is already ordered and already excludes {@code @everyone}.
     * A role-heavy member would blow past the 1024-character field ceiling, so the tail
     * becomes a count rather than taking the embed down.
     */
    static String roleList(List<Role> roles) {
        StringBuilder text = new StringBuilder("🏷️");
        int shown = 0;

        for (Role role : roles) {
            String next = " " + role.getAsMention();
            // Leave room for the "and N more" tail before committing to another mention.
            if (text.length() + next.length() > MessageEmbed.VALUE_MAX_LENGTH - 24)
                break;
            text.append(next);
            shown++;
        }

        int hidden = roles.size() - shown;
        if (hidden > 0)
            text.append(" and ").append(hidden).append(" more");

        return text.toString();
    }

    static List<String> keyPermissions(Member member) {
        List<String> held = new ArrayList<>();

        for (Permission permission : KEY_PERMISSIONS) {
            if (!member.hasPermission(permission))
                continue;

            // Administrator implies every other permission, so listing the rest beside
            // it says nothing. One line is the more honest answer.
            if (permission == Permission.ADMINISTRATOR)
                return List.of(permission.getName());

            held.add(permission.getName());
        }

        return held;
    }
}
