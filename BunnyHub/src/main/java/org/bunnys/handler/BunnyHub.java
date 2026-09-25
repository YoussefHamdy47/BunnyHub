package org.bunnys.handler;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.bunnys.handler.commands.CommandLoader;
import org.bunnys.handler.commands.CommandRegistry;
import org.bunnys.handler.database.DB;
import org.bunnys.handler.database.MongoManager;
import org.bunnys.handler.events.EventLoader;
import org.bunnys.handler.router.buttons.ButtonRouter;
import org.bunnys.handler.router.modals.ModalRouter;
import org.bunnys.handler.utils.TokenLoader;
import org.bunnys.utils.BunnyLog;

import java.util.Scanner;

public class BunnyHub implements AutoCloseable {
    private volatile JDA jda;
    private final BunnyHubConfig config;
    private final long startTime;
    private volatile boolean isRunning = true;
    private final CommandRegistry commandRegistry;
    private final MongoManager mongoManager;
    private final InteractionExecutor interactionExecutor;
    private final InteractionExecutor autocompleteExecutor;
    private Thread shutdownHook;

    BunnyHub(BunnyHubConfig config) {
        this.config = config;
        this.interactionExecutor = new InteractionExecutor("BunnyCommand", config.getCommandPoolSize(), config.getCommandQueueCapacity(), config.getCommandUserCapacity());
        this.autocompleteExecutor = new InteractionExecutor("BunnyAutocomplete", config.getAutocompletePoolSize(), config.getAutocompleteQueueCapacity(), config.getAutocompleteUserCapacity());
        this.startTime = System.currentTimeMillis();
        this.commandRegistry = new CommandRegistry(this, config.getDeveloperIds(), config.getTestServerIds());

        BunnyLog.setLogActions(config.isLogActions());

        String mongoURI = TokenLoader.getEnv(config.getMongoUriKey());
        this.mongoManager = new MongoManager(mongoURI, config.getDatabaseName(),
                config.getCommandPoolSize() + config.getAutocompletePoolSize() + 8, config.getDatabaseTimeout());

        DB.init(this.mongoManager);

        if (!this.mongoManager.isConnected()) {
            interactionExecutor.close();
            autocompleteExecutor.close();
            throw new IllegalStateException("Database startup failed. Check connection settings and unique user indexes.");
        }

        if (config.getDeveloperIds().isEmpty())
            BunnyLog.warning("No Developer IDs provided.");

        if (config.getTestServerIds().isEmpty())
            BunnyLog.warning("No Test Server IDs provided.");

        registerShutdownHook();

        if (config.isAutoLogin()) {
            login();
            startConsoleListener();
        }
    }

    public synchronized void login() {
        if (!isRunning) throw new IllegalStateException("BunnyHub has been shut down.");
        if (this.jda != null)
            return;

        try {
            String token = TokenLoader.getToken(config.getTokenKey());
            net.dv8tion.jda.api.utils.messages.MessageRequest.setDefaultMentions(
                    java.util.EnumSet.of(net.dv8tion.jda.api.entities.Message.MentionType.USER));
            JDABuilder jdaBuilder = JDABuilder.createLight(token, config.getIntents());

            if (config.getButtonPackage() != null)
                ButtonRouter.loadButtons(config.getButtonPackage());

            if (config.getSelectPackage() != null)
                org.bunnys.handler.router.selects.SelectRouter.loadSelects(config.getSelectPackage());

            if (config.getModalPackage() != null)
                ModalRouter.loadModals(config.getModalPackage());

            if (config.getEventPackage() != null)
                EventLoader.loadEvents(this, jdaBuilder, config.getEventPackage());

            if (config.getCommandPackage() != null) {
                CommandLoader.loadCommands(this, this.commandRegistry, config.getCommandPackage());

            }

            this.jda = jdaBuilder.build();

        } catch (Exception e) {
            performShutdown(false);
            throw new IllegalStateException("BunnyHub login failed.", e);
        }
    }

    public void shutdown() {
        performShutdown(false);

    }

    private synchronized void performShutdown(boolean emergency) {
        if (!isRunning)
            return;
        isRunning = false;
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
            catch (IllegalStateException ignored) { /* JVM shutdown already started. */ }
        }

        String mode = emergency ? "emergency fallback" : "graceful";
        BunnyLog.info("[BunnyHub] Initiating " + mode + " shutdown...");

        // Stop both admission paths before waiting for accepted work.
        autocompleteExecutor.shutdown();
        interactionExecutor.shutdown();
        new ShutdownAction("autocomplete workers", autocompleteExecutor::close).run();
        new ShutdownAction("command workers", interactionExecutor::close).run();
        for (ShutdownAction action : config.getShutdownActions()) action.run();
        new ShutdownAction("command registry", commandRegistry::clearCommands).run();
        ButtonRouter.clear();
        ModalRouter.clear();
        org.bunnys.handler.router.selects.SelectRouter.clear();
        new ShutdownAction("event listeners", () -> EventLoader.clearEvents(jda)).run();
        new ShutdownAction("Discord connection", () -> {
            if (jda == null) return;
            if (emergency) jda.shutdownNow(); else jda.shutdown();
            try {
                if (!jda.awaitShutdown(java.time.Duration.ofSeconds(10))) jda.shutdownNow();
            } catch (InterruptedException error) {
                jda.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }).run();
        new ShutdownAction("database", mongoManager::disconnect).run();
        BunnyLog.info("[BunnyHub] Offline");
        System.out.flush();
        System.err.flush();
    }

    @Override public void close() { shutdown(); }

    public static final String HANDLER_VERSION = "2.0.0";
    public String getHandlerVersion() { return HANDLER_VERSION; }
    private void startConsoleListener() {
        Thread consoleThread = new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                while (isRunning && scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim().toLowerCase(java.util.Locale.ROOT);
                    if (line.equals("stop") || line.equals("exit")) shutdown();
                }
            }
        }, "BunnyConsoleListener");

        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private void registerShutdownHook() {
        shutdownHook = new Thread(() -> performShutdown(true), "BunnyEmergencyShutdownHook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public JDA getJDA() {
        return this.jda;
    }

    public void executeForUser(String userId, Runnable action) {
        interactionExecutor.submit(userId, action);
    }

    public void executeAutocomplete(String userId, Runnable action) { autocompleteExecutor.submit(userId, action); }
    public InteractionExecutor.Snapshot getCommandWorkload() { return interactionExecutor.snapshot(); }
    public InteractionExecutor.Snapshot getAutocompleteWorkload() { return autocompleteExecutor.snapshot(); }

    public long getStartTime() {
        return this.startTime;
    }

    public String getVersion() {
        return "4.0.0";
    }

    public java.util.concurrent.ExecutorService getCommandExecutor() {
        return interactionExecutor.executor();
    }

    public String getDeveloperName() { return config.getDeveloperName(); }
    public String getDeveloperUrl() { return config.getDeveloperUrl(); }

    public CommandRegistry getCommandRegistry() {
        return this.commandRegistry;
    }

    public MongoManager getMongoManager() {
        return this.mongoManager;
    }

    public static BunnyHubBuilder create() {
        return new BunnyHubBuilder();
    }
}
