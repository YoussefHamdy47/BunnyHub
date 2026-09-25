package org.bunnys.bunnynexus.alerts.adapters.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.bunnys.bunnynexus.alerts.application.ConfigurationAccess;
import org.bunnys.bunnynexus.alerts.domain.AlertIdentity;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Cache-only preflight: missing/disconnected cache denies access. No REST request or executor is created. */
public final class JdaConfigurationAccess implements ConfigurationAccess {
    private final JDA jda;
    private final Clock clock;
    private final Duration lifetime;
    private final Set<ChannelType> channelTypes;
    public JdaConfigurationAccess(JDA jda, Clock clock, Duration lifetime, Set<ChannelType> channelTypes) {
        this.jda = Objects.requireNonNull(jda); this.clock = Objects.requireNonNull(clock);
        this.lifetime = Objects.requireNonNull(lifetime); this.channelTypes = Set.copyOf(channelTypes);
        if (lifetime.toMillis() < 1 || lifetime.compareTo(Duration.ofSeconds(60)) > 0 || lifetime.getNano() % 1_000_000 != 0
                || this.channelTypes.isEmpty() || !Set.of(ChannelType.TEXT, ChannelType.NEWS).containsAll(this.channelTypes))
            throw new IllegalArgumentException("Explicit supported text/news types and millisecond proof lifetime required.");
    }
    @Override public void requireAdministrator(String actorId, String guildId) { administrator(actorId, guildId); }
    @Override public Proof verify(Requirements requirements) {
        Instant checkedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Guild guild = administrator(requirements.actorId(), requirements.guildId());
        var bot = guild.getSelfMember();
        for (var target : requirements.destinations()) {
            var channel = guild.getGuildChannelById(target.channelId());
            if (!(channel instanceof GuildMessageChannel) || !channel.getGuild().getId().equals(requirements.guildId())
                    || !channelTypes.contains(channel.getType()) || bot.isTimedOut()
                    || !bot.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
                throw new AccessDenied();
            for (String roleId : target.roles()) {
                var role = guild.getRoleById(roleId);
                if (role == null || role.isPublicRole() || !role.getGuild().getId().equals(requirements.guildId())
                        || (!role.isMentionable() && !bot.hasPermission(channel, Permission.MESSAGE_MENTION_EVERYONE)))
                    throw new AccessDenied();
            }
        }
        if (jda.getStatus() != JDA.Status.CONNECTED) throw new AccessDenied();
        return new Proof(requirements, checkedAt, checkedAt.plus(lifetime));
    }
    private Guild administrator(String actorId, String guildId) {
        AlertIdentity.snowflake(actorId); AlertIdentity.snowflake(guildId);
        if (jda.getStatus() != JDA.Status.CONNECTED) throw new AccessDenied();
        Guild guild = jda.getGuildById(guildId);
        if (guild == null || !guild.getId().equals(guildId)) throw new AccessDenied();
        var actor = guild.getMemberById(actorId);
        if (actor == null || !actor.getGuild().getId().equals(guildId)
                || !(actor.hasPermission(Permission.MANAGE_SERVER) || actor.hasPermission(Permission.ADMINISTRATOR)))
            throw new AccessDenied();
        return guild;
    }
}
