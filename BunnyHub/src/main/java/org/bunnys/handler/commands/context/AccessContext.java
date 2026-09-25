package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

/** Identity needed for authorization, independent of an interaction's reply type. */
public interface AccessContext {
    User getUser();
    Member getMember();
    Guild getGuild();
    MessageChannel getChannel();
    default boolean isFromGuild() { return getGuild() != null; }
    record Snapshot(User getUser, Member getMember, Guild getGuild, MessageChannel getChannel)
            implements AccessContext {}
}
