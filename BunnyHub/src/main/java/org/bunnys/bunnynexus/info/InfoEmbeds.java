package org.bunnys.bunnynexus.info;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import org.bunnys.utils.AppDesign;
import org.bunnys.utils.Embeds;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Every embed the informational commands can produce.
 *
 * <p>Rendering only, per the house layering rule - nothing here reads the database or a
 * cache. Everything it needs is passed in, already resolved.
 *
 * <h2>Smart fields</h2>
 * A field that would say "None" is usually a field not worth printing. Roles, boosting,
 * key permissions, nicknames, server descriptions and banners all appear only when there
 * is something to show, so a card stays as short as its subject deserves rather than
 * padding itself out with absences.
 *
 * <h2>Times</h2>
 * Rendered as Discord timestamp markup ({@code <t:epoch:F>}) rather than formatted here.
 * The client renders them in the reader's own locale and timezone, and the relative form
 * keeps counting on its own instead of going stale the moment the message is sent.
 *
 * <h2>What is deliberately missing</h2>
 * No online status, device, or "listening to Spotify". All three need the
 * {@code GUILD_PRESENCES} privileged intent, which Discord no longer grants for this
 * kind of bot. Printing a permanent "Offline" would be worse than printing nothing.
 */
public final class InfoEmbeds {

    /** Discord serves avatars at powers of two; 1024 is the largest that stays quick. */
    private static final int AVATAR_SIZE = 1024;

    /** A field value is capped at 1024 characters. Roles are the only unbounded list. */
    private static final int MAX_FIELD_VALUE = MessageEmbed.VALUE_MAX_LENGTH;


    /** Footer hints, one drawn per card. Plain text, since footers cannot render emoji. */
    private static final String[] TIPS = {
            "Blue text is clickable",
            "Click the user ID to open their profile",
            "Click the colour to see the full shade",
            "This works for people outside this server too",
            "Empty fields are hidden rather than left blank",
            "Timestamps are drawn by your own client, so they never go stale",
            "Administrator outranks everything, so it is listed on its own",
            "Roles are listed highest first",
            "A server avatar only shows when it differs from the global one",
            "Use /avatar to download the original avatar"
    };

    private InfoEmbeds() {}

    private static void appendBotCredit(EmbedBuilder embed, String botVersion,
                                        String developerName, String developerId) {
        if (botVersion != null && !botVersion.isBlank())
            embed.addField("Version", "ℹ️" + " `" + botVersion + "`", true);

        if (developerName != null && !developerName.isBlank())
            embed.addField("Built By", "ℹ️" + " "
                    + credit(developerName, developerId), true);
    }

    /**
     * The developer credit, linked to a Discord profile where one is configured.
     *
     * <p>A profile URL rather than a {@code <@id>} mention on purpose: a mention renders
     * as "@unknown-user" for anybody who does not share a server with them, which makes
     * the credit look broken exactly where it is most likely to be read by a stranger.
     */
    private static String credit(String name, String developerId) {
        return developerId == null
                ? name
                : "[" + name + "](https://discord.com/users/" + developerId + ")";
    }

    /** Null when the member has no avatar of their own for this server. */
    private static String serverAvatar(Member member) {
        if (member == null || member.getAvatarId() == null)
            return null;
        return member.getEffectiveAvatar().getUrl(AVATAR_SIZE);
    }

    private static String globalAvatar(User target) {
        return target.getEffectiveAvatar().getUrl(AVATAR_SIZE);
    }

    private static String avatarLinks(String global, String server) {
        String links = "[Global Avatar](" + global + ")";
        return server == null ? links : links + "  |  [Server Avatar](" + server + ")";
    }

    // ------------------------------------------------------------------
    // User profile
    // ------------------------------------------------------------------

    /**
     * A full profile card.
     *
     * @param member null outside a guild, or when the target is not a member of it -
     *               in which case the guild half is dropped rather than faked
     */
    public static MessageEmbed userInfo(User target, Member member) {
        return userInfo(target, member, null, null, null);
    }

    /**
     * A profile card that credits the developer when the subject is the bot itself.
     *
     * <p>Looking up the bot is the one moment somebody is actually asking "what is this
     * thing and who made it", so that is where the answer belongs. Every other profile -
     * and every other embed in the bot - is left alone: a credit repeated everywhere
     * stops reading as authorship and starts reading as advertising.
     *
     * @param developerName null on any card that is not the bot's own
     */
    public static MessageEmbed userInfo(User target, Member member, String botVersion,
                                        String developerName, String developerId) {
        if (member == null)
            return strangerInfo(target, botVersion, developerName, developerId);

        long created = target.getTimeCreated().toEpochSecond();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("👤 " + displayName(target, member), profileUrl(target))
                .setThumbnail(member.getEffectiveAvatarUrl())
                .setDescription("👤" + " " + target.getAsMention() + "\n"
                        + avatarLinks(globalAvatar(target), serverAvatar(member)))
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("TIP: " + tip() + " • " + Embeds.FOOTER)
                .setTimestamp(Instant.now());

        String badges = MemberDetails.badges(target);
        if (badges != null)
            embed.addField("Badges", "🏅" + " " + badges, false);

        embed.addField("Joined Discord", "📅" + " " + moment(created), true);

        if (member.hasTimeJoined())
            embed.addField("Joined Server", "📥" + " "
                    + moment(member.getTimeJoined().toEpochSecond()), true);

        embed.addField("Account Type", "👤" + " "
                + (target.isBot() ? "Bot" : "Human"), true);

        embed.addField("Username", "👤" + " `" + target.getName() + "`", true);

        // Only worth a field when it differs from the name already in the author line.
        if (member.getNickname() != null)
            embed.addField("Nickname", "🎨" + " " + member.getNickname(), true);

        embed.addField("User ID", " ["
                + target.getId() + "](" + profileUrl(target) + ")", true);

        String colour = MemberDetails.colourField(member);
        if (colour != null)
            embed.addField("Colour", colour, true);

        embed.addField("Highest Role", "🏷️" + " " + MemberDetails.highestRole(member), true);

        if (member.isBoosting() && member.getTimeBoosted() != null)
            embed.addField("Boosting Since", "💎" + " "
                    + moment(member.getTimeBoosted().toEpochSecond()), true);

        List<Role> roles = member.getRoles();
        if (!roles.isEmpty())
            embed.addField("Roles [" + roles.size() + "]", MemberDetails.roleList(roles), false);

        List<String> keyPermissions = MemberDetails.keyPermissions(member);
        if (!keyPermissions.isEmpty())
            embed.addField("Key Permissions [" + keyPermissions.size() + "]",
                    clamp("🔑" + " " + String.join(", ", keyPermissions)), false);

        appendBotCredit(embed, botVersion, developerName, developerId);

        return embed.build();
    }

    /**
     * The reduced card for somebody who is not in this server.
     *
     * <p>Everything guild-shaped is genuinely unknowable here, so the card says less
     * rather than printing a column of dashes.
     */
    private static MessageEmbed strangerInfo(User target, String botVersion,
                                             String developerName, String developerId) {
        long created = target.getTimeCreated().toEpochSecond();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("👤 " + target.getEffectiveName(), profileUrl(target))
                .setThumbnail(globalAvatar(target))
                .setDescription("👤" + " " + target.getAsMention() + "\n"
                        + avatarLinks(globalAvatar(target), null))
                .addField("Joined Discord", "📅" + " " + moment(created), true)
                .addField("Account Type", "👤" + " "
                        + (target.isBot() ? "Bot" : "Human"), true)
                .addField("Username", "👤" + " `" + target.getName() + "`", true)
                .addField("User ID", "ℹ️" + " ["
                        + target.getId() + "](" + profileUrl(target) + ")", true)
                .setColor(AppDesign.ColorCodes.DEFAULT)
                .setFooter("TIP: This shows much more for members of this server • " + Embeds.FOOTER)
                .setTimestamp(Instant.now());

        String badges = MemberDetails.badges(target);
        if (badges != null)
            embed.addField("Badges", "🏅" + " " + badges, false);

        appendBotCredit(embed, botVersion, developerName, developerId);

        return embed.build();
    }

    // ------------------------------------------------------------------
    // Failures
    // ------------------------------------------------------------------

    /**
     * {@code /info server} was run outside a guild.
     *
     * <p>{@code CommandGate} gates whole commands, not branches, and {@code /info user}
     * is perfectly useful in a DM - so this one branch turns itself away rather than
     * taking the other one down with it.
     */
    public static MessageEmbed guildOnly() {
        return Embeds.error("Server Only", "There is no server to describe here. Run this one in a server.");
    }

    /**
     * The mention path could not resolve the named user.
     *
     * <p>Only reachable from a mention: a slash {@code USER} option is resolved by
     * Discord itself and always arrives complete.
     */
    public static MessageEmbed unknownUser() {
        return Embeds.error("User Not Found", "I could not work out who that is. Mention them directly, or use the slash command.");
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    private static String displayName(User target, Member member) {
        return member != null ? member.getEffectiveName() : target.getEffectiveName();
    }

    private static String profileUrl(User target) {
        return "https://discord.com/users/" + target.getId();
    }

    /** Full date plus a live relative counter, both rendered client-side. */
    static String moment(long epochSeconds) {
        return "<t:" + epochSeconds + ":F> (<t:" + epochSeconds + ":R>)";
    }

    private static String tip() {
        return TIPS[ThreadLocalRandom.current().nextInt(TIPS.length)];
    }

    static String clamp(String value) {
        return value.length() <= MAX_FIELD_VALUE
                ? value
                : value.substring(0, MAX_FIELD_VALUE - 3) + "...";
    }
}
