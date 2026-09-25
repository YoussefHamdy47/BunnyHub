package org.bunnys.handler.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bunnys.utils.BunnyLog;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

public class MongoManager {
    private MongoClient client;
    private MongoDatabase database;
    private volatile boolean connected = false;

    public MongoManager(String uri, String databaseName) {
        this(uri, databaseName, 32);
    }

    public MongoManager(String uri, String databaseName, int poolSize) {
        this(uri, databaseName, poolSize, java.time.Duration.ofSeconds(10));
    }

    public MongoManager(String uri, String databaseName, int poolSize, java.time.Duration operationTimeout) {
        if (uri == null || uri.isBlank()) {
            BunnyLog.error("[Database] No MongoDB URI provided.");
            return;
        }

        try {
            MongoClientSettings settings = settings(uri, poolSize, operationTimeout);

            this.client = MongoClients.create(settings);
            this.database = this.client.getDatabase(databaseName);

            this.database.runCommand(new Document("ping", 1));
            Document topology = this.database.runCommand(new Document("hello", 1));
            if (!topology.containsKey("setName") && !"isdbgrid".equals(topology.getString("msg")))
                throw new IllegalStateException("BunnyHub requires a MongoDB replica set or sharded cluster for transactions.");
            this.database.getCollection("BunnyUsers").createIndex(
                    com.mongodb.client.model.Indexes.ascending("userID"),
                    new com.mongodb.client.model.IndexOptions().unique(true));
            this.database.getCollection("TimerData").createIndex(
                    com.mongodb.client.model.Indexes.ascending("account.userID"),
                    new com.mongodb.client.model.IndexOptions().unique(true));
            this.database.getCollection("SemesterHistory").createIndex(
                    com.mongodb.client.model.Indexes.ascending("userID", "archivedAt"));
            this.connected = true;
            BunnyLog.success("[Database] Successfully connected to MongoDB cluster!");

        } catch (Exception e) {
            BunnyLog.error("[Database] Critical failure establishing connection", e);
            disconnect();
        }
    }

    /** Pure configuration factory so timeout policy can be verified without connecting. */
    static MongoClientSettings settings(String uri, int poolSize, java.time.Duration timeout) {
        if (poolSize < 1 || timeout == null || timeout.toMillis() < 1)
            throw new IllegalArgumentException("Pool size and database timeout must be positive.");
        long millis = timeout.toMillis();
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .timeout(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(Math.min(5000, millis), java.util.concurrent.TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout((int) Math.min(5000, millis), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout((int) Math.min(Integer.MAX_VALUE, millis), java.util.concurrent.TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool.maxSize(poolSize)
                        .maxWaitTime(Math.min(2000, millis), java.util.concurrent.TimeUnit.MILLISECONDS))
                .codecRegistry(fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                        fromProviders(PojoCodecProvider.builder().automatic(true).build())))
                .build();
    }

    public <T> MongoCollection<T> getCollection(Class<T> clazz, String collectionName) {
        if (!connected) throw new IllegalStateException("Database is offline.");
        return database.getCollection(collectionName, clazz);
    }

    public MongoDatabase getDatabase() {
        return this.database;
    }

    public boolean isConnected() {
        return this.connected;
    }

    public synchronized void disconnect() {
        this.connected = false;
        this.database = null;
        if (this.client != null) {
            this.client.close();
            this.client = null;
            BunnyLog.info("[Database] Connection pool closed cleanly.");
        }
    }

    public com.mongodb.client.ClientSession startSession() {
        if (!connected) throw new IllegalStateException("Database is offline.");
        return client.startSession();
    }
}
