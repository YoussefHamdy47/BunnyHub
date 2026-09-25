package org.bunnys.bunnynexus.alerts.runtime;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GamerPowerPollingLoopTest {
    @Test void startupSchedulesImmediateCheckAndRecurrenceWithoutACommand() {
        var executor = mock(ScheduledExecutorService.class); var future = mock(ScheduledFuture.class);
        doReturn(future).when(executor).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(15000L), eq(TimeUnit.MILLISECONDS));
        var calls = new AtomicInteger(); var stopped = new AtomicInteger();
        var loop = new GamerPowerPollingLoop(executor, () -> { calls.incrementAndGet(); return GamerPowerPoller.Result.NOT_DUE; }, stopped::incrementAndGet);
        assertEquals(0, calls.get());
        loop.start();
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(task.capture(), eq(0L), eq(15000L), eq(TimeUnit.MILLISECONDS));
        task.getValue().run(); task.getValue().run();
        assertEquals(2, calls.get()); assertEquals(GamerPowerPoller.Result.NOT_DUE, loop.lastResult().orElseThrow());
        assertThrows(IllegalStateException.class, loop::start);
        loop.close(); loop.close(); task.getValue().run();
        assertEquals(2, calls.get()); assertEquals(1, stopped.get()); verify(future).cancel(false);
        verify(executor, never()).shutdown();
    }
    @Test void failedTickDoesNotSilentlyKillAllFutureChecks() {
        var executor = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(executor).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        var count = new AtomicInteger();
        var loop = new GamerPowerPollingLoop(executor, () -> {
            if (count.getAndIncrement() == 0) throw new IllegalStateException("synthetic");
            return GamerPowerPoller.Result.RECORDED;
        }, () -> {});
        loop.start(); var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any());
        task.getValue().run(); assertEquals(GamerPowerPoller.Result.UNCERTAIN, loop.lastResult().orElseThrow());
        task.getValue().run(); assertEquals(GamerPowerPoller.Result.RECORDED, loop.lastResult().orElseThrow());
        loop.close();
    }
    @Test void schedulingFailureClosesResourcesAndPreventsRestart() {
        var executor = mock(ScheduledExecutorService.class); var stop = mock(Runnable.class);
        when(executor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenThrow(new RejectedExecutionException());
        var loop = new GamerPowerPollingLoop(executor, () -> GamerPowerPoller.Result.NOT_DUE, stop);
        assertThrows(RejectedExecutionException.class, loop::start); verify(stop).run();
        assertThrows(IllegalStateException.class, loop::start);
    }
    @Test void closeDuringSchedulingCancelsThePublishedFuture() {
        var executor = mock(ScheduledExecutorService.class); var future = mock(ScheduledFuture.class);
        var loop = new GamerPowerPollingLoop(executor, () -> { fail("Closed loop must not poll"); return GamerPowerPoller.Result.CLOSED; }, () -> {});
        doAnswer(invocation -> { loop.close(); ((Runnable) invocation.getArgument(0)).run(); return future; })
                .when(executor).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        loop.start(); verify(future).cancel(false);
    }
}
