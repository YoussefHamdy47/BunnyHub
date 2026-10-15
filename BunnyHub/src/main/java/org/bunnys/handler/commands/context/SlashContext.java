package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/** A {@link CommandContext} for a slash command. */
public final class SlashContext extends InteractionContext {
    public SlashContext(SlashCommandInteractionEvent event) { super(event); }
    @Override public SlashCommandInteractionEvent event() { return (SlashCommandInteractionEvent) event; }
    @Override public boolean isSlash() { return true; }
}