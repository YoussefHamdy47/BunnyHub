package org.bunnys.handler;

/**
 * A long-lived feature component (schedulers, background workers, in-memory menus) whose lifecycle the handler
 * owns. Implementations in the service package are discovered like commands and events: they need a public
 * no-argument constructor, are started once after login, and are stopped during shutdown in reverse order.
 */
public interface BunnyService {
    /** Label used in startup and shutdown logs. */
    default String name() { return getClass().getSimpleName(); }

    /** Called once after the client is built. Must not block for long; start background work instead. */
    default void start(BunnyHub client) {}

    /** Stop background work and release resources promptly. Failures are logged and never skip other services. */
    void shutdown();
}
