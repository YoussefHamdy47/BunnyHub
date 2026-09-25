package org.bunnys.handler;
import java.util.List;
import net.dv8tion.jda.api.requests.GatewayIntent;
/** Immutable startup settings, detached from the builder. */
public final class BunnyHubConfig {
    private final int autocompletePoolSize;
    private final int autocompleteQueueCapacity;
    private final java.time.Duration databaseTimeout;
    private final boolean logActions;
    private final boolean autoLogin;
    private final String tokenKey;
    private final List<GatewayIntent> intents;
    private final String eventPackage;
    private final List<String> developerIds;
    private final String developerName;
    private final String developerUrl;
    private final List<String> testServerIds;
    private final String commandPackage;
    private final String buttonPackage;
    private final String modalPackage;
    private final String selectPackage;
    private final String databaseName;
    private final String mongoUriKey;
    private final int commandPoolSize;
    private final int commandQueueCapacity;
    private final List<ShutdownAction> shutdownActions;
    private final int commandUserCapacity;
    private final int autocompleteUserCapacity;
    BunnyHubConfig(BunnyHubBuilder builder) {
        commandUserCapacity = builder.getCommandUserCapacity();
        autocompleteUserCapacity = builder.getAutocompleteUserCapacity();
        this.autocompletePoolSize = builder.getAutocompletePoolSize();
        this.autocompleteQueueCapacity = builder.getAutocompleteQueueCapacity();
        this.databaseTimeout = builder.getDatabaseTimeout();
        this.logActions = builder.isLogActions();
        this.autoLogin = builder.isAutoLogin();
        this.tokenKey = builder.getTokenKey();
        this.intents = List.copyOf(builder.getIntents());
        this.eventPackage = builder.getEventPackage();
        this.developerIds = List.copyOf(builder.getDeveloperIds());
        this.developerName = builder.getDeveloperName();
        this.developerUrl = builder.getDeveloperUrl();
        this.testServerIds = List.copyOf(builder.getTestServerIds());
        this.commandPackage = builder.getCommandPackage();
        this.buttonPackage = builder.getButtonPackage();
        this.modalPackage = builder.getModalPackage();
        this.selectPackage = builder.getSelectPackage();
        this.databaseName = builder.getDatabaseName();
        this.mongoUriKey = builder.getMongoUriKey();
        this.commandPoolSize = builder.getCommandPoolSize();
        this.commandQueueCapacity = builder.getCommandQueueCapacity();
        shutdownActions = List.copyOf(builder.getShutdownActions());
    }
    public int getAutocompletePoolSize() { return autocompletePoolSize; }
    public int getAutocompleteQueueCapacity() { return autocompleteQueueCapacity; }
    public java.time.Duration getDatabaseTimeout() { return databaseTimeout; }
    public boolean isLogActions() { return logActions; }
    public boolean isAutoLogin() { return autoLogin; }
    public String getTokenKey() { return tokenKey; }
    public List<GatewayIntent> getIntents() { return intents; }
    public String getEventPackage() { return eventPackage; }
    public List<String> getDeveloperIds() { return developerIds; }
    public String getDeveloperName() { return developerName; }
    public String getDeveloperUrl() { return developerUrl; }
    public List<String> getTestServerIds() { return testServerIds; }
    public String getCommandPackage() { return commandPackage; }
    public String getButtonPackage() { return buttonPackage; }
    public String getModalPackage() { return modalPackage; }
    public String getSelectPackage() { return selectPackage; }
    public String getDatabaseName() { return databaseName; }
    public String getMongoUriKey() { return mongoUriKey; }
    public int getCommandPoolSize() { return commandPoolSize; }
    public int getCommandQueueCapacity() { return commandQueueCapacity; }
    public List<ShutdownAction> getShutdownActions() { return shutdownActions; }
    public int getCommandUserCapacity() { return commandUserCapacity; }
    public int getAutocompleteUserCapacity() { return autocompleteUserCapacity; }
}
