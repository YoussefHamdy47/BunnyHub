package org.bunnys.bunnynexus.alerts;

import org.bunnys.bunnynexus.alerts.application.OwnershipRepository;
import org.bunnys.bunnynexus.alerts.runtime.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeOwnershipTest {
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");
    private final AtomicLong nanos = new AtomicLong();
    private final SendAdmission admission = new SendAdmission(2);
    private final OwnershipRepository repository = mock(OwnershipRepository.class);
    private final RuntimeOwnership ownership = new RuntimeOwnership(repository, admission, "process", Duration.ofSeconds(10), Duration.ofSeconds(1), nanos::get);
    private OwnershipRepository.Lease lease() { return new OwnershipRepository.Lease(new OwnershipRepository.Runtime(), "process", 1, NOW, NOW.plusSeconds(10)); }
    private void acquired() { when(repository.acquire(any(), anyString(), any())).thenReturn(Optional.of(lease())); assertTrue(ownership.acquire()); }

    @Test void localValidityIsConservativeAndExpiryCannotAutomaticallyReacquire() {
        acquired(); nanos.set(Duration.ofSeconds(8).toNanos()); assertTrue(ownership.active());
        nanos.set(Duration.ofSeconds(9).toNanos()); assertFalse(ownership.active());
        assertEquals(RuntimeOwnership.State.LOST, ownership.state()); assertTrue(admission.snapshot().closed());
        assertFalse(ownership.acquire()); assertFalse(ownership.renew());
        verify(repository, times(1)).acquire(any(), anyString(), any());
    }
    @Test void slowDatabaseResponseDoesNotCreateFreshLocalTime() {
        when(repository.acquire(any(), anyString(), any())).thenAnswer(call -> { nanos.set(Duration.ofSeconds(9).toNanos()); return Optional.of(lease()); });
        assertFalse(ownership.acquire()); assertTrue(admission.snapshot().closed());
    }
    @Test void knownRenewalExtendsValidityButCannotResurrectAnExpiredOwner() {
        acquired(); nanos.set(Duration.ofSeconds(5).toNanos());
        when(repository.renew(any(), any())).thenReturn(Optional.of(lease())); assertTrue(ownership.renew());
        nanos.set(Duration.ofSeconds(10).toNanos()); assertTrue(ownership.active());
        when(repository.renew(any(), any())).thenAnswer(call -> { nanos.set(Duration.ofSeconds(15).toNanos()); return Optional.of(lease()); });
        assertFalse(ownership.renew()); assertEquals(RuntimeOwnership.State.LOST, ownership.state());
    }
    @Test void unknownRenewalStopsAdmissionAndRetainsProtectedWork() {
        acquired(); var permit = admission.tryReserve("200").orElseThrow(); permit.protectAttempt();
        when(repository.renew(any(), any())).thenThrow(new IllegalStateException("uncertain database write"));
        assertThrows(IllegalStateException.class, ownership::renew);
        assertEquals(1, admission.snapshot().occupied()); assertTrue(admission.snapshot().closed());
    }
    @Test void closeDoesNotWaitForDatabaseOrAcceptItsLateAcquireReply() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(repository.acquire(any(), anyString(), any())).thenAnswer(call -> { entered.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); return Optional.of(lease()); });
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(ownership::acquire);
            try { assertTrue(entered.await(5, TimeUnit.SECONDS)); ownership.close(); assertEquals(RuntimeOwnership.State.CLOSED, ownership.state()); }
            finally { finish.countDown(); }
            assertFalse(pending.get(5, TimeUnit.SECONDS)); assertTrue(admission.snapshot().closed());
        }
    }
    @Test void releaseRequiresCloseAndProvenLocalDrain() {
        acquired(); var permit = admission.tryReserve("200").orElseThrow(); permit.protectAttempt();
        assertFalse(ownership.releaseAfterDrain()); ownership.close(); assertFalse(ownership.releaseAfterDrain());
        permit.confirmTransportStopped(); assertFalse(ownership.releaseAfterDrain());
        permit.confirmReceiptRecorded(); when(repository.release(any())).thenReturn(true);
        assertTrue(ownership.releaseAfterDrain()); verify(repository).release(lease());
    }
}
