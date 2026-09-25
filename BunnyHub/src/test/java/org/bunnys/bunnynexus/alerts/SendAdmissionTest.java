package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.runtime.SendAdmission;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SendAdmissionTest {
    @Test void receiptFailureAlsoStopsPreviouslyReservedAuthorizations() {
        var admission = new SendAdmission(3);
        var active = admission.tryReserve("100").orElseThrow();
        var waiting = admission.tryReserve("200").orElseThrow();
        active.protectAttempt();
        active.receiptPersistenceFailed();
        assertThrows(IllegalStateException.class, waiting::protectAttempt);
        assertEquals(2, admission.snapshot().occupied());
        active.confirmReceiptRecorded();
        waiting.protectAttempt();
        waiting.authorizationRejected();
        assertEquals(1, admission.snapshot().occupied());
        active.confirmTransportStopped();
        assertEquals(0, admission.snapshot().occupied());
    }
    @Test void boundsGlobalAndPerChannelCapacityWithoutQueueing() {
        var admission = new SendAdmission(2);
        var first = admission.tryReserve("100").orElseThrow();
        assertTrue(admission.tryReserve("100").isEmpty());
        assertTrue(admission.tryReserve("200").isPresent());
        assertTrue(admission.tryReserve("300").isEmpty());
        first.cancelBeforeAttempt();
        assertTrue(admission.tryReserve("300").isPresent());
        assertEquals(2, admission.snapshot().occupied());
    }
    @Test void receiptDoesNotFreePotentiallyLiveTransportAndCloseRetainsIt() {
        var admission = new SendAdmission(1);
        var permit = admission.tryReserve("100").orElseThrow();
        permit.protectAttempt();
        permit.confirmReceiptRecorded();
        assertEquals(1, admission.snapshot().occupied());
        assertThrows(IllegalStateException.class, permit::cancelBeforeAttempt);
        admission.close();
        assertEquals(1, admission.snapshot().occupied());
        permit.confirmTransportStopped();
        assertEquals(0, admission.snapshot().occupied());
        assertTrue(admission.tryReserve("100").isEmpty());
    }
    @Test void receiptFailurePausesAllAdmissionUntilKnownCommit() {
        var admission = new SendAdmission(3);
        var first = admission.tryReserve("100").orElseThrow();
        var second = admission.tryReserve("200").orElseThrow();
        first.protectAttempt(); second.protectAttempt();
        first.confirmTransportStopped(); first.receiptPersistenceFailed(); first.receiptPersistenceFailed();
        second.receiptPersistenceFailed();
        assertEquals(2, admission.snapshot().persistenceFailures());
        assertTrue(admission.tryReserve("300").isEmpty());
        first.confirmReceiptRecorded();
        assertTrue(admission.tryReserve("300").isEmpty());
        second.confirmReceiptRecorded();
        assertTrue(admission.tryReserve("300").isPresent());
        assertEquals(2, admission.snapshot().occupied()); // second transport still potentially live
    }
    @Test void lateCallbacksCannotReleaseAnotherInvocationOnSameChannel() {
        var admission = new SendAdmission(1);
        var old = admission.tryReserve("100").orElseThrow();
        old.protectAttempt(); old.confirmTransportStopped(); old.confirmReceiptRecorded();
        var current = admission.tryReserve("100").orElseThrow();
        current.protectAttempt();
        old.confirmTransportStopped(); old.confirmReceiptRecorded(); old.authorizationRejected(); old.receiptPersistenceFailed();
        assertEquals(1, admission.snapshot().occupied());
        assertEquals(0, admission.snapshot().persistenceFailures());
    }
    @Test void rejectedAuthorizationCanReleaseButShutdownPreventsNewAuthorization() {
        var admission = new SendAdmission(1);
        var permit = admission.tryReserve("100").orElseThrow();
        permit.protectAttempt(); permit.authorizationRejected();
        var reserved = admission.tryReserve("100").orElseThrow();
        admission.close();
        assertThrows(IllegalStateException.class, reserved::protectAttempt);
        reserved.cancelBeforeAttempt();
        assertEquals(0, admission.snapshot().occupied());
    }
    @Test void simultaneousReservationsCannotExceedCapacity() throws Exception {
        var admission = new SendAdmission(8);
        var accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 100; i < 200; i++) {
                String channel = Integer.toString(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    if (admission.tryReserve(channel).isPresent()) accepted.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(8, accepted.get());
        assertEquals(8, admission.snapshot().occupied());
    }
}
