package org.bunnys.bunnynexus.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.Embeds;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Ban, unban, kick, mute and unmute. Mute is Discord's own timeout, so a muted member can't chat, react or join
 * voice until it ends, with nothing for the bot to undo later.
 *
 * <p>Every action checks, before touching Discord, that the moderator and the bot both have the permission and
 * both rank above the target, and refuses with a plain-language reason otherwise. Refusals are private; a
 * successful action is announced in the channel.
 */
public final class Moderation {
    private Moderation() {}

    public static final int MAX_REASON = 400;
    /** Discord's longest timeout. */
    public static final Duration MAX_MUTE = Duration.ofDays(28);

    public enum Action {
        BAN("ban", "banned", Permission.BAN_MEMBERS, false),
        UNBAN("unban", "unbanned", Permission.BAN_MEMBERS, false),
        KICK("kick", "kicked", Permission.KICK_MEMBERS, true),
        MUTE("mute", "muted", Permission.MODERATE_MEMBERS, true),
        UNMUTE("unmute", "unmuted", Permission.MODERATE_MEMBERS, true);

        private final String verb, past;
        private final Permission permission;
        private final boolean needsMember;

        Action(String verb, String past, Permission permission, boolean needsMember) {
            this.verb = verb; this.past = past; this.permission = permission; this.needsMember = needsMember;
        }

        public String verb() { return verb; }
        public String past() { return past; }
        public Permission permission() { return permission; }
        /** False for ban and unban, which also work on people who aren't in the server. */
        public boolean needsMember() { return needsMember; }
    }

    // ------------------------------------------------------------------ options

    public static OptionData userOption(String description) {
        return new OptionData(OptionType.USER, "user", description, true);
    }

    public static OptionData reasonOption() {
        return new OptionData(OptionType.STRING, "reason", "Shown in the audit log and the confirmation", false)
                .setMaxLength(MAX_REASON);
    }

    /** Minutes. Choices keep it to one click; Discord allows up to 28 days. */
    public static OptionData durationOption() {
        return new OptionData(OptionType.INTEGER, "duration", "How long (default: 1 hour)", false)
                .addChoice("1 minute", 1).addChoice("5 minutes", 5).addChoice("10 minutes", 10)
                .addChoice("30 minutes", 30).addChoice("1 hour", 60).addChoice("6 hours", 360)
                .addChoice("12 hours", 720).addChoice("1 day", 1440).addChoice("3 days", 4320)
                .addChoice("1 week", 10080).addChoice("2 weeks", 20160).addChoice("28 days", 40320);
    }

    /** Hours of the user's recent messages to delete. */
    public static OptionData deleteMessagesOption() {
        return new OptionData(OptionType.INTEGER, "delete_messages", "Also delete their recent messages (default: none)", false)
                .addChoice("Don't delete any", 0).addChoice("Last hour", 1).addChoice("Last 24 hours", 24)
                .addChoice("Last 7 days", 168);
    }

    // ------------------------------------------------------------------ checks

    /**
     * Why this action can't be done to {@code target}, or empty when it can. {@code target} may be null for ban
     * and unban (someone who isn't in the server), in which case only the permissions are checked.
     */
    public static Optional<String> problem(Action action, Member moderator, Member self, User targetUser, Member target) {
        if (moderator == null || !moderator.hasPermission(action.permission()))
            return Optional.of("You need the **" + action.permission().getName() + "** permission to do that.");
        if (!self.hasPermission(action.permission()))
            return Optional.of("I need the **" + action.permission().getName() + "** permission to do that.");
        if (targetUser.getIdLong() == moderator.getIdLong())
            return Optional.of("You can't " + action.verb() + " yourself.");
        if (targetUser.getIdLong() == self.getIdLong())
            return Optional.of("I can't " + action.verb() + " myself.");
        if (action == Action.UNBAN) return Optional.empty();
        if (target == null)
            return action.needsMember() ? Optional.of(name(targetUser) + " isn't in this server.") : Optional.empty();
        if (target.isOwner())
            return Optional.of("The server owner can't be " + action.past() + ".");
        if (!moderator.canInteract(target))
            return Optional.of(name(targetUser) + " has a role equal to or higher than yours.");
        if (!self.canInteract(target))
            return Optional.of(name(targetUser) + "'s highest role is above mine. Move my role higher in Server Settings → Roles.");
        if (action == Action.MUTE && target.hasPermission(Permission.ADMINISTRATOR))
            return Optional.of("Discord doesn't allow muting administrators.");
        if (action == Action.UNMUTE && !target.isTimedOut())
            return Optional.of(name(targetUser) + " isn't muted.");
        return Optional.empty();
    }

    // ------------------------------------------------------------------ running

    /**
     * Checks, acts and replies. Runs on a command worker without a deferral, so a refusal from the local checks
     * is private while a success is public.
     */
    public static void run(Action action, CommandContext ctx) {
        Guild guild = ctx.getGuild();
        User user = ctx.getUserOption("user");
        if (guild == null || user == null) {
            refuse(ctx, action, "Pick a member of this server.");
            return;
        }
        Member target = ctx.getMemberOption("user");
        var problem = problem(action, ctx.getMember(), guild.getSelfMember(), user, target);
        if (problem.isPresent()) { refuse(ctx, action, problem.get()); return; }

        String reason = ctx.getString("reason");
        String audit = auditReason(ctx.getUser(), reason);
        int minutes = Math.max(1, Math.min(ctx.getInt("duration", 60), (int) MAX_MUTE.toMinutes()));
        int deleteHours = Math.max(0, Math.min(ctx.getInt("delete_messages", 0), 168));

        AuditableRestAction<Void> request = switch (action) {
            case BAN -> guild.ban(user, deleteHours, TimeUnit.HOURS);
            case UNBAN -> guild.unban(user);
            case KICK -> guild.kick(user);
            // A minute of slack at the 28-day limit, so the request can't land just past Discord's maximum.
            case MUTE -> guild.timeoutFor(user, minutes >= MAX_MUTE.toMinutes() ? MAX_MUTE.minusMinutes(1) : Duration.ofMinutes(minutes));
            case UNMUTE -> guild.removeTimeout(user);
        };
        // Acknowledge before the request: a rate-limited ban can outlast Discord's three-second window. Every
        // refusal above was private; a failure from here on is shown briefly and then deleted.
        ctx.defer(false);
        try {
            request.reason(audit).complete();
        } catch (ErrorResponseException failed) {
            refuse(ctx, action, failure(action, user, failed.getErrorResponse()));
            return;
        } catch (HierarchyException | InsufficientPermissionException failed) {
            refuse(ctx, action, "Discord refused: I don't have the permission or a high enough role to do that.");
            return;
        } catch (RuntimeException failed) {
            refuse(ctx, action, "Discord couldn't " + action.verb() + " " + name(user) + " right now. Please try again.");
            return;
        }
        ctx.reply(done(action, user, ctx.getUser(), reason, minutes, deleteHours));
    }

    static String failure(Action action, User user, ErrorResponse error) {
        return switch (error) {
            case UNKNOWN_BAN -> name(user) + " isn't banned.";
            case UNKNOWN_MEMBER -> name(user) + " isn't in this server.";
            case MISSING_PERMISSIONS -> "Discord refused: I don't have the permission or a high enough role to "
                    + action.verb() + " " + name(user) + ".";
            default -> "Discord couldn't " + action.verb() + " " + name(user) + " right now. Please try again.";
        };
    }

    /** The public confirmation. */
    public static MessageEmbed done(Action action, User target, User moderator, String reason, int minutes, int deleteHours) {
        String title = switch (action) {
            case BAN -> "Member banned";
            case UNBAN -> "Member unbanned";
            case KICK -> "Member kicked";
            case MUTE -> "Member muted";
            case UNMUTE -> "Member unmuted";
        };
        StringBuilder text = new StringBuilder("**").append(name(target)).append("** (").append(target.getAsMention())
                .append(") was ").append(action.past()).append(" by ").append(moderator.getAsMention()).append(".");
        if (action == Action.MUTE) {
            long until = Instant.now().plus(Duration.ofMinutes(minutes)).getEpochSecond();
            text.append("\nMuted for ").append(durationLabel(minutes)).append(", until <t:").append(until).append(":f>.");
        }
        if (action == Action.BAN && deleteHours > 0)
            text.append("\nTheir messages from the last ").append(deleteHours == 1 ? "hour" : deleteHours == 24 ? "24 hours" : "7 days")
                    .append(" were deleted.");
        text.append("\n**Reason:** ").append(reason == null || reason.isBlank() ? "None given" : MarkdownSanitizer.escape(reason.strip()));
        return Embeds.of(null, title, text.toString()).build();
    }

    static String durationLabel(int minutes) {
        if (minutes % 1440 == 0) {
            int days = minutes / 1440;
            if (days % 7 == 0 && days != 28) return days / 7 == 1 ? "1 week" : days / 7 + " weeks";
            return days == 1 ? "1 day" : days + " days";
        }
        if (minutes % 60 == 0) return minutes / 60 == 1 ? "1 hour" : minutes / 60 + " hours";
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    /** Audit log entries show who used the bot, since Discord records the bot as the actor. */
    static String auditReason(User moderator, String reason) {
        String text = moderator.getName() + ": " + (reason == null || reason.isBlank() ? "no reason given" : reason.strip());
        return text.length() <= 512 ? text : text.substring(0, 511) + "…";
    }

    private static void refuse(CommandContext ctx, Action action, String text) {
        ctx.replyTransient(Embeds.error("Couldn't " + action.verb(), text));
    }

    private static String name(User user) {
        return MarkdownSanitizer.escape(user.getName(), true);
    }
}
