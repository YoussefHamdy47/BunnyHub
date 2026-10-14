package org.bunnys.bunnynexus.setup;

import java.util.List;
import java.util.Optional;

/**
 * One server's welcome message, goodbye message and join roles. A message is on when it has a channel; clearing the
 * channel turns it off but keeps the text, so turning it back on later doesn't lose the wording.
 */
public record ServerSettings(Greeting welcome, Greeting goodbye, List<String> joinRoles) {
    public static final int MAX_JOIN_ROLES = 5;
    public static final int MAX_MESSAGE = 1000;

    public ServerSettings {
        welcome = welcome == null ? new Greeting(null, null) : welcome;
        goodbye = goodbye == null ? new Greeting(null, null) : goodbye;
        joinRoles = joinRoles == null ? List.of() : List.copyOf(joinRoles);
    }

    public static ServerSettings empty() { return new ServerSettings(null, null, List.of()); }

    public enum Kind {
        WELCOME("welcome", "Welcome message", "Welcome to **{server}**, {user}! You're member number {count}."),
        GOODBYE("goodbye", "Goodbye message", "**{name}** has left the server. We now have {count} members.");

        private final String key, label, defaultMessage;

        Kind(String key, String label, String defaultMessage) {
            this.key = key; this.label = label; this.defaultMessage = defaultMessage;
        }

        public String key() { return key; }
        public String label() { return label; }
        public String defaultMessage() { return defaultMessage; }

        public static Optional<Kind> byKey(String key) {
            for (Kind kind : values()) if (kind.key.equals(key)) return Optional.of(kind);
            return Optional.empty();
        }
    }

    /** {@code channelId} null = off. {@code message} null = the default text for its kind. */
    public record Greeting(String channelId, String message) {
        public boolean on() { return channelId != null; }
        public String text(Kind kind) { return message == null || message.isBlank() ? kind.defaultMessage() : message; }
    }

    public Greeting greeting(Kind kind) { return kind == Kind.WELCOME ? welcome : goodbye; }

    public ServerSettings with(Kind kind, Greeting greeting) {
        return kind == Kind.WELCOME ? new ServerSettings(greeting, goodbye, joinRoles) : new ServerSettings(welcome, greeting, joinRoles);
    }

    public ServerSettings withJoinRoles(List<String> roles) { return new ServerSettings(welcome, goodbye, roles); }

    public boolean isEmpty() { return !welcome.on() && !goodbye.on() && joinRoles.isEmpty(); }
}
