package org.bunnys.bunnynexus.alerts.adapters.mongo;

import com.mongodb.client.model.*;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bunnys.bunnynexus.alerts.application.OwnerReviewRepository;
import org.bunnys.bunnynexus.alerts.domain.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoTestSupport.*;
import static org.bunnys.bunnynexus.alerts.adapters.mongo.MongoOwnerReviewSchema.*;

class MongoOwnerReviewTest {
    static AlertDisplayContent content(Offer o) {
        return new AlertDisplayContent(o.key(), o.contentRevision(), "Game", "A long description\n" + "x".repeat(300),
                URI.create("https://store.example.com/game"), "Source", URI.create("https://source.example.com/"));
    }
    static String material(Offer o) { return PublicationReviewPolicy.materialHash(o, content(o)); }
    static Document released(Offer o) {
        return AlertDocuments.base(CatalogDocuments.offerId(o.key())).append("ownerId", "900").append("revision", 3L)
                .append("state", "RELEASED").append("offer", CatalogDocuments.offer(o)).append("content", OwnerReviewDocuments.content(content(o)))
                .append("materialHash", material(o)).append("templateVersion", "v1").append("previewUntil", Date.from(o.verifiedAt().plusSeconds(900)))
                .append("verifiedAt", Date.from(o.verifiedAt())).append("releasedAt", Date.from(o.verifiedAt()))
                .append("releaseUntil", Date.from(o.endsAt().orElseThrow().isBefore(o.verifiedAt().plusSeconds(3600)) ? o.endsAt().orElseThrow() : o.verifiedAt().plusSeconds(3600)))
                .append("maximumAttempts", 10).append("usedAttempts", 0).append("checkedAt", Date.from(o.verifiedAt()));
    }
    MongoOwnerReviewRepository repository(MongoTestSupport h) { return new MongoOwnerReviewRepository(h.db, "900", MongoCatalogTest.POLICY); }
    MongoTestSupport configured(Document current) {
        var h = new MongoTestSupport();
        when(h.collection(AUDIT).find(eq(h.session), any(Bson.class))).thenAnswer(ignored -> find());
        when(h.collection(REVIEWS).findOneAndUpdate(eq(h.session), any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class))).thenReturn(current);
        when(h.collection(REVIEWS).replaceOne(eq(h.session), any(Bson.class), any(Document.class), any(ReplaceOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        return h;
    }
    @Test void onlyConfiguredOwnerCanReadOrChangeReview() {
        var h = new MongoTestSupport(); var repo = repository(h); var o = MongoCatalogTest.offer(1);
        assertThrows(SecurityException.class, () -> repo.get("901", o.key()));
        assertThrows(SecurityException.class, () -> repo.prepare("901", "500", 0, o, content(o)));
        assertThrows(SecurityException.class, () -> repo.verify("901", "500", o.key(), 1));
        assertThrows(SecurityException.class, () -> repo.release("901", "500", o.key(), 2, 10));
        assertThrows(SecurityException.class, () -> repo.revoke("901", "500", o.key(), 3));
        assertTrue(h.collections.isEmpty());
    }
    @Test void preparePersistsBoundedExactPreviewAndDisablesPreviousRelease() {
        var o = MongoCatalogTest.offer(1); var h = configured(released(o));
        assertEquals(OwnerReviewRepository.Status.APPLIED, repository(h).prepare("900", "500", 3, o, content(o)).status());
        var d = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(REVIEWS)).replaceOne(eq(h.session), any(Bson.class), d.capture(), any(ReplaceOptions.class));
        var snapshot = OwnerReviewDocuments.read(d.getValue());
        assertEquals(OwnerReviewRepository.State.PENDING, snapshot.state()); assertEquals(content(o), snapshot.content());
        assertEquals(0, snapshot.maximumAttempts()); assertTrue(snapshot.releaseUntil().isEmpty());
    }
    @Test void verifyDoesNotReleaseAndReleaseRequiresVerification() {
        var o = MongoCatalogTest.offer(1); var pending = released(o); pending.put("state", "PENDING"); pending.put("revision", 1L);
        var h = configured(pending);
        assertEquals(OwnerReviewRepository.Status.CONFLICT, repository(h).release("900", "501", o.key(), 1, 100).status());
        assertEquals(OwnerReviewRepository.Status.APPLIED, repository(h).verify("900", "502", o.key(), 1).status());
        var d = ArgumentCaptor.forClass(Document.class);
        verify(h.collection(REVIEWS)).replaceOne(eq(h.session), any(Bson.class), d.capture(), any(ReplaceOptions.class));
        assertEquals("VERIFIED", d.getValue().getString("state"));
    }
    @Test void stalePreviewAndStaleEvidenceCannotRelease() {
        for (boolean expired : List.of(true, false)) {
            var o = MongoCatalogTest.offer(1); var d = released(o); d.put("state", "VERIFIED");
            if (expired) d.put("previewUntil", Date.from(NOW)); else d.put("checkedAt", Date.from(NOW.plusSeconds(700)));
            var h = configured(d);
            assertThrows(IllegalStateException.class, () -> repository(h).release("900", "500", o.key(), 3, 10));
            verify(h.collection(AUDIT), never()).insertOne(eq(h.session), any(Document.class));
        }
    }
    @Test void unknownCommitIsNeverReportedAsSuccessfulRelease() {
        var o = MongoCatalogTest.offer(1); var d = released(o); d.put("state", "VERIFIED");
        var h = configured(d);
        doThrow(new IllegalStateException("unknown commit")).when(h.collection(AUDIT)).insertOne(eq(h.session), any(Document.class));
        assertThrows(IllegalStateException.class, () -> repository(h).release("900", "500", o.key(), 3, 10));
    }
    @Test void tamperedContentAndOverlongReleaseCannotBeDecoded() {
        var o = MongoCatalogTest.offer(1); var d = released(o);
        d.get("content", Document.class).put("title", "Changed");
        assertThrows(IllegalArgumentException.class, () -> OwnerReviewDocuments.read(d));
        var longRelease = released(o); longRelease.put("releaseUntil", Date.from(NOW.plusSeconds(4000)));
        assertThrows(IllegalArgumentException.class, () -> OwnerReviewDocuments.read(longRelease));
    }
    @Test void reservationUsesCurrentStateRevisionDeadlineAndCap() {
        var h = new MongoTestSupport(); var d = released(MongoCatalogTest.offer(1));
        when(h.collection(REVIEWS).updateOne(eq(h.session), any(Bson.class), any(Bson.class), any(UpdateOptions.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        assertTrue(MongoOwnerReviewRepository.reserve(h.session, h.db, d));
        var filter = ArgumentCaptor.forClass(Bson.class);
        verify(h.collection(REVIEWS)).updateOne(eq(h.session), filter.capture(), any(Bson.class), any(UpdateOptions.class));
        String bson = bson(filter.getValue());
        for (String field : List.of("RELEASED", "revision", "ownerId", "$$NOW", "$releaseUntil", "$usedAttempts", "$maximumAttempts"))
            assertTrue(bson.contains(field), bson);
    }
}

