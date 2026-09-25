package org.bunnys.handler.commands.context;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.UserContextInteractionEvent;

/** A user-menu invocation: the caller remains the actor and the selected user is the target. */
public final class UserContext extends InteractionContext {
    public UserContext(UserContextInteractionEvent event) { super(event); }
    @Override public UserContextInteractionEvent event() { return (UserContextInteractionEvent) event; }
    @Override public boolean isSlash() { return false; }
    @Override public String invocationLabel(String path) { return "Apps → " + event.getName(); }
    @Override public User getUserOption(String name) { return "user".equals(name) ? event().getTarget() : null; }
    @Override public Member getMemberOption(String name) { return "user".equals(name) ? event().getTargetMember() : null; }
    @Override public String getString(String name) { return "user".equals(name) ? event().getTarget().getId() : null; }
    @Override public Integer getInt(String name) { return null; }
    @Override public Boolean getBool(String name) { return null; }
}
