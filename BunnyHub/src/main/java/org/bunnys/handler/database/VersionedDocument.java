package org.bunnys.handler.database;

/** Nullable revision allows existing MongoDB documents to be upgraded on their next save. */
public interface VersionedDocument {
    Long getRevision();
    void setRevision(Long revision);
}
