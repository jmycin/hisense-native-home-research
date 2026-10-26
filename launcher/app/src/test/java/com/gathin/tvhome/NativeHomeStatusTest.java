package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

/** Tests the presentation decision used by the real control Activity, not Android rendering. */
public class NativeHomeStatusTest {
    @Test public void savedOptInWithoutAnObserverIsPausedNotRunning() {
        assertEquals(NativeHomeStatus.Phase.PAUSED,
                NativeHomeStatus.phase(true, false, false, false, "stopped"));
    }
    @Test public void pendingStartAppearsBeforeServiceDeliveryAndDisablesStart() {
        NativeHomeStatus.Phase phase = NativeHomeStatus.phase(true, true, false, false, "stopped");
        assertEquals(NativeHomeStatus.Phase.STARTING, phase);
        assertFalse(NativeHomeStatus.canStart(phase));
    }
    @Test public void aNewPendingAttemptSupersedesTheLastFailureDisplay() {
        assertEquals(NativeHomeStatus.Phase.STARTING,
                NativeHomeStatus.phase(true, true, false, false, "startup_timeout"));
    }
    @Test public void runningRequiresOptInSessionReadinessAndRuntimeObserver() {
        assertEquals(NativeHomeStatus.Phase.RUNNING,
                NativeHomeStatus.phase(true, false, true, true, "none"));
        assertEquals(NativeHomeStatus.Phase.PAUSED,
                NativeHomeStatus.phase(true, false, true, false, "none"));
        assertEquals(NativeHomeStatus.Phase.PAUSED,
                NativeHomeStatus.phase(true, false, false, true, "none"));
        assertEquals(NativeHomeStatus.Phase.OFF,
                NativeHomeStatus.phase(false, false, true, true, "none"));
        assertFalse(NativeHomeStatus.canStart(NativeHomeStatus.Phase.RUNNING));
    }
    @Test public void clockFailureRemainsVisibleAfterItsStopClearsOptIn() {
        NativeHomeStatus.Phase phase = NativeHomeStatus.phase(false, false, false, false, "startup_timeout");
        assertEquals(NativeHomeStatus.Phase.FAILED, phase);
        assertTrue(NativeHomeStatus.canStart(phase));
    }
    @Test public void reportedFailureWinsEvenBeforeTheObserverFlagsAreCleared() {
        assertEquals(NativeHomeStatus.Phase.FAILED,
                NativeHomeStatus.phase(true, false, true, true, "query_failed"));
    }
    @Test public void runtimeFlagsWithoutConfirmedClockDoNotClaimRunning() {
        assertEquals(NativeHomeStatus.Phase.PAUSED,
                NativeHomeStatus.phase(true, false, true, true, "pending"));
        assertEquals(NativeHomeStatus.Phase.PAUSED,
                NativeHomeStatus.phase(true, false, true, true, null));
    }
    @Test public void stopWhileObserverIsTearingDownDoesNotClaimItIsRunning() {
        assertEquals(NativeHomeStatus.Phase.OFF,
                NativeHomeStatus.phase(false, false, false, true, "pending"));
    }
    @Test public void coldProcessWithSavedOptInIsPausedAndCanRetry() {
        NativeHomeSession cold = new NativeHomeSession();
        NativeHomeStatus.Phase phase = NativeHomeStatus.phase(true, cold.starting(), cold.accepting(), false, "stopped");
        assertEquals(NativeHomeStatus.Phase.PAUSED, phase);
        assertTrue(NativeHomeStatus.canStart(phase));
        assertTrue(cold.requestStartIfIdle(() -> null).queued());
    }
    @Test public void ordinaryOffStateCanStart() {
        NativeHomeStatus.Phase phase = NativeHomeStatus.phase(false, false, false, false, "stopped");
        assertEquals(NativeHomeStatus.Phase.OFF, phase);
        assertTrue(NativeHomeStatus.canStart(phase));
    }
}
