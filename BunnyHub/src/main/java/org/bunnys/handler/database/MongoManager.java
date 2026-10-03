package org.bunnys.handler.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bunnys.utils.BunnyLog;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

/** Owns the MongoDB client. Requires a replica set or sharded cluster, because account updates use transactions. */
public final class MongoManager {
    private MongoClient client;
    private MongoDatabase database;
    private volatile boolean connected = false;

    public MongoManager(String uri, String databaseName) {
        this(uri, databaseName, 32);
    }

    public MongoManager(String uri, String databaseName, int poolSize) {
        this(uri, databaseName, poolSize, Duration.ofSeconds(10));
    }

    public MongoManager(String uri, String databaseName, int poolSize, Duration operationTimeout) {
        if (uri == null || uri.isBlank()) {
            BunnyLog.error("[Database] No MongoDB URI provided.");
            return;
        }
        try {
            client = MongoClients.create(settings(uri, poolSize, operationTimeout));
            database = client.getDatabase(databaseName);
            database.runCommand(new Document("ping", 1));
            Document topology = database.runCommand(new Document("hello", 1));
            if (!topology.containsKey("setName") && !"isdbgrid".equals(topology.getString("msg")))
                throw new IllegalStateException("BunnyHub requires a MongoDB replica set or sharded cluster for transactions.");
            database.getCollection("BunnyUsers").createIndex(Indexes.ascending("userID"), new IndexOptions().unique(true));
            database.getCollection("TimerData").createIndex(Indexes.ascending("account.userID"), new IndexOptions().unique(true));
            database.getCollection("SemesterHistory").createIndex(Indexes.ascending("userID", "archivedAt"));
            connected = true;
            BunnyLog.success("[Database] Successfully connected to MongoDB cluster!");
        } catch (Exception e) {
            BunnyLog.error("[Database] Critical failure establishing connection", e);
            disconnect();
        }
    }

    /** Pure configuration factory so timeout policy can be verified without connecting. */
    static MongoClientSettings settings(String uri, int poolSize, Duration timeout) {
        if (poolSize < 1 || timeout == null || timeout.toMillis() < 1)
            throw new IllegalArgumentException("Pool size and database timeout must be positive.");
        long millis = timeout.toMillis();
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .timeout(millis, TimeUnit.MILLISECONDS)
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(Math.min(5000, millis), TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket -> socket.connectTimeout((int) Math.min(5000, millis), TimeUnit.MILLISECONDS)
                        .readTimeout((int) Math.min(Integer.MAX_VALUE, millis), TimeUnit.MILLISECONDS))
                .applyToConnectionPoolSettings(pool -> pool.maxSize(poolSize)
                        .maxWaitTime(Math.min(2000, millis), TimeUnit.MILLISECONDS))
                .codecRegistry(fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                        fromProviders(PojoCodecProvider.builder().automatic(true).build())))
                .build();
    }

    public <T> MongoCollection<T> getCollection(Class<T> clazz, String collectionName) {
        if (!connected) throw new IllegalStateException("Database is offline.");
        return database.getCollection(collectionName, clazz);
    }

    public MongoDatabase getDatabase() {
        return database;
    }

    public boolean isConnected() {
        return connected;
    }

    public synchronized void disconnect() {
        connected = false;
        database = null;
        if (client != null) {
            client.close();
            client = null;
            BunnyLog.info("[Database] Connection pool closed cleanly.");
        }
    }

    public ClientSession startSession() {
        if (!connected) throw new IllegalStateException("Database is offline.");
        return client.startSession();
    }
}
