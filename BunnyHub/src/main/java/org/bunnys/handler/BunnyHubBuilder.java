package org.bunnys.handler;

import net.dv8tion.jda.api.requests.GatewayIntent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BunnyHubBuilder {
    // Default feature states
    private boolean logActions = true;
    private boolean autoLogin = true;
    private String tokenKey = "DISCORD_TOKEN";


    private String eventPackage = null;
    private String commandPackage = null;
    private String buttonPackage = null;
    private String modalPackage = null;
    private String selectPackage = null;

    /** MongoDB connection details. The database name is not this bot's to assume. */
    private String databaseName = "GBF";
    private String mongoUriKey = "MongoURI";

    /**
     * Command worker count and queue depth.
     *
     * <p>Commands wait on Mongo and Discord REST, so workers can comfortably
     * outnumber cores. The default is sized for a small host serving one busy guild.
     */
    private int commandPoolSize = 24;
    private int commandQueueCapacity = 100;
    private int autocompletePoolSize = 4;
    private int autocompleteQueueCapacity = 32;
    private int commandUserCapacity = 8;
    private int autocompleteUserCapacity = 2;

    public BunnyHubBuilder setPerUserCapacity(int commands, int autocomplete) {
        if (commands < 1 || autocomplete < 1) throw new IllegalArgumentException("Per-user capacities must be positive.");
        commandUserCapacity = commands;
        autocompleteUserCapacity = autocomplete;
        return this;
    }
    public int getCommandUserCapacity() { return commandUserCapacity; }
    public int getAutocompleteUserCapacity() { return autocompleteUserCapacity; }
    private java.time.Duration databaseTimeout = java.time.Duration.ofSeconds(10);

    public BunnyHubBuilder setAutocompletePool(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException("Autocomplete pool sizes must be positive.");
        autocompletePoolSize = workers;
        autocompleteQueueCapacity = queueCapacity;
        return this;
    }

    public BunnyHubBuilder setDatabaseTimeout(java.time.Duration timeout) {
        if (timeout == null || timeout.toMillis() < 1) throw new IllegalArgumentException("Database timeout must be positive.");
        databaseTimeout = timeout;
        return this;
    }

    public int getAutocompletePoolSize() { return autocompletePoolSize; }
    public int getAutocompleteQueueCapacity() { return autocompleteQueueCapacity; }
    public java.time.Duration getDatabaseTimeout() { return databaseTimeout; }

    // Explicitly empty by default
    private final List<GatewayIntent> intents = new ArrayList<>();
    private final List<String> developerIds = new ArrayList<>();

    /** See {@link #setDeveloperCredit(String, String)}. Null means no credit is shown. */
    private String developerName;
    private String developerUrl;
    private final List<String> testServerIds = new ArrayList<>();

    public BunnyHubBuilder setLogActions(boolean logActions) {
        this.logActions = logActions;
        return this;
    }

    public BunnyHubBuilder setAutoLogin(boolean autoLogin) {
        this.autoLogin = autoLogin;
        return this;
    }

    public BunnyHubBuilder setTokenKey(String tokenKey) {
        if (tokenKey == null || tokenKey.isBlank()) throw new IllegalArgumentException("Token environment key is required."); this.tokenKey = tokenKey;
        return this;
    }

    public BunnyHubBuilder addIntents(GatewayIntent... gatewayIntents) {
        this.intents.addAll(Arrays.asList(gatewayIntents));
        return this;
    }

    public BunnyHubBuilder setEventPackage(String packageName) {
        this.eventPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setCommandPackage(String packageName) {
        this.commandPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setButtonPackage(String packageName) {
        this.buttonPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setModalPackage(String packageName) {
        this.modalPackage = packageName;
        return this;
    }

    public BunnyHubBuilder setSelectPackage(String packageName) {
        this.selectPackage = packageName;
        return this;
    }

    /**
     * The MongoDB database name.
     *
     * <p>Defaults to {@code GBF}, which is only correct for the original cluster. Anyone
     * pointing this bot at their own MongoDB will have their own database name, and it
     * should not require editing the handler to say so.
     */
    public BunnyHubBuilder setDatabaseName(String databaseName) {
        if (databaseName == null || databaseName.isBlank()) throw new IllegalArgumentException("Database name is required.");
        this.databaseName = databaseName;
        return this;
    }

    /** The environment key holding the connection string. Defaults to {@code MongoURI}. */
    public BunnyHubBuilder setMongoUriKey(String mongoUriKey) {
        if (mongoUriKey == null || mongoUriKey.isBlank()) throw new IllegalArgumentException("Mongo URI environment key is required.");
        this.mongoUriKey = mongoUriKey;
        return this;
    }

    /**
     * How many commands may run at once, and how many may wait.
     *
     * <p>The worker count is the real throughput ceiling: every slash command, mention,
     * button, select, modal and autocomplete runs on this pool. Raise it if commands
     * start being rejected under load - the router logs "rejected under load" when that
     * happens - and remember the Mongo connection pool is sized from this number, so the
     * two stay in step automatically.
     *
     * <p>The queue is deliberately shallow. A deep queue does not add capacity, it only
     * converts a fast rejection into a long wait for a user who has already given up.
     *
     * @param workers        concurrent commands; must be at least one
     * @param queueCapacity  how many may wait before new invocations are refused
     */
    public BunnyHubBuilder setCommandPool(int workers, int queueCapacity) {
        if (workers < 1)
            throw new IllegalArgumentException("Command pool needs at least one worker.");
        if (queueCapacity < 1)
            throw new IllegalArgumentException("Command queue needs at least one slot.");

        this.commandPoolSize = workers;
        this.commandQueueCapacity = queueCapacity;
        return this;
    }

    public BunnyHubBuilder addDeveloperIds(String... ids) {
        this.developerIds.addAll(Arrays.asList(ids));
        return this;
    }

    /**
     * Who built the bot, shown on {@code /uptime}.
     *
     * <p>Configuration rather than a string buried in a feature, for two reasons. It
     * belongs to the deployment, not to the uptime command - and whoever runs this
     * should be able to change or drop it by editing one line here, instead of going
     * looking for it. Leave it unset and the credit simply does not render.
     *
     * <p>The Discord profile link comes from the first entry in {@code addDeveloperIds}.
     *
     * @param name the name to credit
     * @param url  somewhere to find them - a repository, a portfolio - or null
     */
    public BunnyHubBuilder setDeveloperCredit(String name, String url) {
        this.developerName = name;
        this.developerUrl = url;
        return this;
    }

    public BunnyHubBuilder addTestServerIds(String... ids) {
        this.testServerIds.addAll(Arrays.asList(ids));
        return this;
    }

    // Getters so BunnyHub can read the configuration safely


    public boolean isLogActions() {
        return logActions;
    }

    public boolean isAutoLogin() {
        return autoLogin;
    }

    public String getTokenKey() {
        return tokenKey;
    }

    public List<GatewayIntent> getIntents() {
        return List.copyOf(intents);
    }

    public String getEventPackage() {
        return eventPackage;
    }

    public List<String> getDeveloperIds() {
        return List.copyOf(developerIds);
    }

    public String getDeveloperName() {
        return developerName;
    }

    public String getDeveloperUrl() {
        return developerUrl;
    }

    public List<String> getTestServerIds() {
        return List.copyOf(testServerIds);
    }

    public String getCommandPackage() {
        return commandPackage;
    }

    public String getButtonPackage() {
        return buttonPackage;
    }

    public String getModalPackage() {
        return modalPackage;
    }

    public String getSelectPackage() {
        return selectPackage;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getMongoUriKey() {
        return mongoUriKey;
    }

    public int getCommandPoolSize() {
        return commandPoolSize;
    }

    public int getCommandQueueCapacity() {
        return commandQueueCapacity;
    }

    /**
     * Builds and returns the BunnyHub instance based on this configuration.
     */
    private final List<ShutdownAction> shutdownActions = new ArrayList<>();

    public BunnyHubBuilder onShutdown(String name, Runnable action) {
        shutdownActions.add(new ShutdownAction(name, action));
        return this;
    }

    List<ShutdownAction> getShutdownActions() { return List.copyOf(shutdownActions); }

    public BunnyHubConfig snapshot() { return new BunnyHubConfig(this); }

    public BunnyHub build() {
        return new BunnyHub(snapshot());
    }
}
