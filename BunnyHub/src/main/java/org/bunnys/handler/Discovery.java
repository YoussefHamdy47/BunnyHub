package org.bunnys.handler;

/**
 * The kinds of classes the handler finds on its own, and the folder (sub-package of the base package) each is
 * looked for in by default. Rename a folder with {@link BunnyHubBuilder#setFolder(Discovery, String)}.
 */
public enum Discovery {
    COMMANDS("commands"),
    EVENTS("events"),
    BUTTONS("buttons"),
    MODALS("modals"),
    SELECTS("selects"),
    SERVICES("services");

    private final String defaultFolder;

    Discovery(String defaultFolder) { this.defaultFolder = defaultFolder; }

    public String defaultFolder() { return defaultFolder; }
}
