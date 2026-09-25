package org.bunnys.bunnynexus.alerts.adapters.mongo;

import org.bson.Document;
import java.util.*;

/** One bounded, order-sensitive index verification policy for all alert migrations. Never repairs indexes. */
final class MongoIndexVerifier {
    private final MongoAlertDatabase db;
    MongoIndexVerifier(MongoAlertDatabase db) { this.db = Objects.requireNonNull(db); }
    void verify(List<MongoAlertSchema.IndexSpec> specifications) {
        Map<String, Map<String, Document>> loaded = new HashMap<>();
        for (var spec : specifications) {
            Document actual = loaded.computeIfAbsent(spec.collection(), this::readIndexes).get(spec.name());
            if (!matches(spec, actual)) throw new IllegalStateException("Missing/incompatible alert index: " + spec.name());
        }
    }
    private Map<String, Document> readIndexes(String collection) {
        Map<String, Document> found = new HashMap<>();
        try (var cursor = db.collection(collection).listIndexes().batchSize(32).iterator()) {
            while (cursor.hasNext()) {
                if (found.size() >= 64) throw new IllegalStateException("Unexpected index count.");
                Document index = cursor.next();
                if (found.put(AlertDocuments.string(index, "name"), index) != null)
                    throw new IllegalStateException("Duplicate index name.");
            }
        }
        return found;
    }
    static boolean matches(MongoAlertSchema.IndexSpec spec, Document actual) {
        if (actual == null || !(actual.get("key") instanceof Document keys)) return false;
        // Document.equals uses unordered Map equality; compound index field order is semantically significant.
        return new ArrayList<>(spec.keys().entrySet()).equals(new ArrayList<>(keys.entrySet()))
                && spec.unique() == Boolean.TRUE.equals(actual.get("unique"))
                && Objects.equals(spec.options().getPartialFilterExpression(), actual.get("partialFilterExpression"))
                && !actual.containsKey("expireAfterSeconds") && !Boolean.TRUE.equals(actual.get("hidden"))
                && !Boolean.TRUE.equals(actual.get("sparse")) && (!actual.containsKey("collation")
                    || new Document("locale", "simple").equals(actual.get("collation")));
    }
}
