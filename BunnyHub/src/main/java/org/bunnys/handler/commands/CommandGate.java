package org.bunnys.handler.commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel;
import org.bunnys.handler.commands.context.CommandContext;
import org.bunnys.utils.SystemEmbeds;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Every pre-execution check, in one place, for both entry points.
 *
 * <p>These rules - developer-only, admin-only, DM, NSFW, permissions, cooldown - used to
 * live inline in {@code InteractionListener}. Adding mention routing would have meant a
 * second copy, and a second copy is how a permission check quietly stops matching its
 * twin. The gate runs against a {@link CommandContext}, so slash and mention are
 * governed by literally the same code.
 *
 * <p>Returns the denial embed to show, or null to proceed.
 */
public final class CommandGate {

    /**
     * Cooldown deadlines, keyed {@code command.subcommand:userId}.
     *
     * <p>Previously a nested {@code ConcurrentHashMap} that was swept by hand on every
     * invocation and emergency-cleared past 5000 entries - an eviction strategy that
     * dropped live cooldowns for everyone. Caffeine expires entries individually.
     */
    private static final org.bunnys.handler.CooldownStore COOLDOWNS = new org.bunnys.handler.CooldownStore("gate.cooldowns", 100_000);
    private CommandGate() {}

    /**
     * @param command    the resolved command
     * @param subcommand the resolved subcommand, or null
     * @return an embed to reply with, or null when the command may run
     */
    public static MessageEmbed check(CommandContext ctx, CommandRegistry registry,
                                     BunnyCommand command, BunnySubcommand subcommand) {

        var denial = checkAccess(ctx, registry, command, subcommand);
        if (denial != null) return denial;
        if (command.isDeveloperBypass() && registry.getDeveloperIds().contains(ctx.getUser().getId())) return null;
        return checkCooldown(ctx, command, subcommand);
    }

    /** Autocomplete must authorize reads without consuming the execution cooldown. */
    public static MessageEmbed checkAccess(org.bunnys.handler.commands.context.AccessContext ctx,
            CommandRegistry registry, BunnyCommand command, BunnySubcommand subcommand) {

        boolean isDeveloper = registry.getDeveloperIds().contains(ctx.getUser().getId());
        if (command.isDeveloperBypass() && isDeveloper)
            return null;

        // 1. Developer gate
        boolean devOnly = (subcommand != null && subcommand.isDeveloperOnly()) || command.isDeveloperOnly();
        if (devOnly && !isDeveloper)
            return SystemEmbeds.denied("Access Denied",
                    "This command is restricted to the bot's developers.");

        // 2. Administrator gate. Beastars-specific role policies are not part of Nexus.
        boolean adminOnly = (subcommand != null && subcommand.isAdminOnly()) || command.isAdminOnly();
        if (adminOnly && (ctx.getMember() == null || !ctx.getMember().hasPermission(Permission.ADMINISTRATOR)))
            return SystemEmbeds.denied("Access Denied",
                    "You must be a Server Administrator to use this.");

        // 3. Guild-only gate
        if (!command.isDmEnabled() && !ctx.isFromGuild())
            return SystemEmbeds.denied("Server Only",
                    "`" + command.getName() + "` cannot be used in direct messages.");

        if (ctx.isFromGuild()) {
            // 5. Age-restricted channels
            boolean nsfw = (subcommand != null && subcommand.isNsfw()) || command.isNsfw();
            if (nsfw && !(ctx.getChannel() instanceof IAgeRestrictedChannel age && age.isNSFW()))
                return SystemEmbeds.denied("Age-Restricted",
                        "This command only works in age-restricted channels.");

            // 6. Caller permissions
            for (Permission permission : command.getUserPermissions())
                if ((ctx.getMember() == null || !(ctx.getChannel() instanceof net.dv8tion.jda.api.entities.channel.middleman.GuildChannel channel) || !ctx.getMember().hasPermission(channel, permission)))
                    return SystemEmbeds.denied("Missing Permission",
                            "You need the `" + permission.getName() + "` permission to use this.");

            // 7. Bot permissions
            for (Permission permission : command.getAppPermissions())
                // A detached guild (user-installed app, bot not a member) has no self member;
                // asking for one throws, and the bot cannot act there anyway.
                if (ctx.getGuild().isDetached()
                        || !(ctx.getChannel() instanceof net.dv8tion.jda.api.entities.channel.middleman.GuildChannel channel)
                        || !ctx.getGuild().getSelfMember().hasPermission(channel, permission))
                    return SystemEmbeds.error("Missing Bot Permission",
                            "I am missing the `" + permission.getName() + "` permission here.");
        }

        if (command.isTestOnly() && (!ctx.isFromGuild() || !registry.getTestServerIds().contains(ctx.getGuild().getId())))
            return SystemEmbeds.denied("Test Server Only", "This command is only available in configured test servers.");
        if (!ctx.isFromGuild() && (command.isNsfw() || (subcommand != null && subcommand.isNsfw())))
            return SystemEmbeds.denied("Age-Restricted", "Use this command in an age-restricted server channel.");

        return null;
    }

    private static MessageEmbed checkCooldown(CommandContext ctx, BunnyCommand command, BunnySubcommand subcommand) {
        int seconds = (subcommand != null && subcommand.getCooldown() > 0)
                ? subcommand.getCooldown()
                : command.getCooldown();

        if (seconds <= 0)
            return null;

        String path = command.pathOf(subcommand);
        String key = path + ":" + ctx.getUser().getId();
        var reservation = COOLDOWNS.reserve(key, TimeUnit.SECONDS.toMillis(seconds));
        if (reservation.remainingMillis() > 0)
            return SystemEmbeds.rateLimited(ctx.getUser().getAsMention(),
                    (System.currentTimeMillis() + reservation.remainingMillis() + 999) / 1000L);
        ctx.recordCooldown(path, reservation);
        return null;
    }

    /** Only the invocation that owns the claim may release it. */
    public static void release(CommandContext ctx, BunnyCommand command, BunnySubcommand subcommand) {
        ctx.releaseCooldown(command.pathOf(subcommand));
    }
    /** Resolves the options in play, which differ between a command and its subcommand. */
    public static List<net.dv8tion.jda.api.interactions.commands.build.OptionData> optionsFor(
            BunnyCommand command, BunnySubcommand subcommand) {
        return subcommand != null ? subcommand.getOptions() : command.getOptions();
    }
}
