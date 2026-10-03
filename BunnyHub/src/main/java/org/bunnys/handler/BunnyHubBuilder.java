package org.bunnys.handler;

import net.dv8tion.jda.api.requests.GatewayIntent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public class BunnyHubBuilder {
    // Default feature states
    private boolean logActions = true;
    private boolean autoLogin = true;
    private String tokenKey = "DISCORD_TOKEN";


    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z\\d_$]*(\\.[A-Za-z_$][A-Za-z\\d_$]*)*");

    // Resolved per kind, independent of call order: an explicit package wins, else <base>.<folder>, else none.
    private String basePackage;
    private final Map<Discovery, String> folders = new EnumMap<>(Discovery.class);
    private final Map<Discovery, String> packages = new EnumMap<>(Discovery.class);

    /**
     * Root package for everything the handler discovers. Each kind is looked for in a folder below it:
     * {@code commands}, {@code events}, {@code buttons}, {@code modals}, {@code selects} and {@code services}
     * by default; rename one with {@link #setFolder(Discovery, String)}.
     */
    public BunnyHubBuilder setBasePackage(String basePackage) {
        this.basePackage = requirePackage(basePackage, "Base package");
        return this;
    }

    /**
     * Looks for one kind in a differently named folder under the base package, e.g.
     * {@code setFolder(Discovery.COMMANDS, "bunnycmds")} scans {@code <base>.bunnycmds}. May contain dots.
     */
    public BunnyHubBuilder setFolder(Discovery kind, String folder) {
        folders.put(Objects.requireNonNull(kind, "kind"), requirePackage(folder, kind + " folder"));
        return this;
    }

    /** Scans exactly {@code packageName} for this kind, ignoring the base package. {@code null} clears the override. */
    public BunnyHubBuilder setPackage(Discovery kind, String packageName) {
        Objects.requireNonNull(kind, "kind");
        if (packageName == null) packages.remove(kind);
        else packages.put(kind, requirePackage(packageName, kind + " package"));
        return this;
    }

    /** The package scanned for {@code kind}, or null when that kind is not discovered. */
    public String getPackage(Discovery kind) {
        String explicit = packages.get(kind);
        if (explicit != null) return explicit;
        return basePackage == null ? null : basePackage + "." + folders.getOrDefault(kind, kind.defaultFolder());
    }

    private static String requirePackage(String name, String what) {
        if (name == null || !PACKAGE_NAME.matcher(name).matches())
            throw new IllegalArgumentException(what + " must be a Java package name, got: " + name);
        return name;
    }

    public BunnyHubBuilder setServicePackage(String packageName) { return setPackage(Discovery.SERVICES, packageName); }
    public String getServicePackage() { return getPackage(Discovery.SERVICES); }

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
    private Duration databaseTimeout = Duration.ofSeconds(10);

    public BunnyHubBuilder setAutocompletePool(int workers, int queueCapacity) {
        if (workers < 1 || queueCapacity < 1) throw new IllegalArgumentException("Autocomplete pool sizes must be positive.");
        autocompletePoolSize = workers;
        autocompleteQueueCapacity = queueCapacity;
        return this;
    }

    public BunnyHubBuilder setDatabaseTimeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() < 1) throw new IllegalArgumentException("Database timeout must be positive.");
        databaseTimeout = timeout;
        return this;
    }

    public int getAutocompletePoolSize() { return autocompletePoolSize; }
    public int getAutocompleteQueueCapacity() { return autocompleteQueueCapacity; }
    public Duration getDatabaseTimeout() { return databaseTimeout; }

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

    public BunnyHubBuilder setEventPackage(String packageName) { return setPackage(Discovery.EVENTS, packageName); }
    public BunnyHubBuilder setCommandPackage(String packageName) { return setPackage(Discovery.COMMANDS, packageName); }
    public BunnyHubBuilder setButtonPackage(String packageName) { return setPackage(Discovery.BUTTONS, packageName); }
    public BunnyHubBuilder setModalPackage(String packageName) { return setPackage(Discovery.MODALS, packageName); }
    public BunnyHubBuilder setSelectPackage(String packageName) { return setPackage(Discovery.SELECTS, packageName); }

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

    public String getEventPackage() { return getPackage(Discovery.EVENTS); }

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

    public String getCommandPackage() { return getPackage(Discovery.COMMANDS); }
    public String getButtonPackage() { return getPackage(Discovery.BUTTONS); }
    public String getModalPackage() { return getPackage(Discovery.MODALS); }
    public String getSelectPackage() { return getPackage(Discovery.SELECTS); }

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
