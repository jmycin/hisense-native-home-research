package com.gathin.tvhome;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Interactions between source.2 admission/failure ownership and process-recovery admission. */
public class NativeHomeRecoveryMergeTest {
    private final NativeHomeSession session = new NativeHomeSession();

    private long recover() {
        long ticket = session.requestProcessRecovery(() -> true, () -> true, () -> true);
        assertTrue(ticket > 0);
        return ticket;
    }

    @Test public void recoveryAndDuplicateUserStartHaveOneDeliveryOwner() {
        long recovered = recover();
        assertFalse(session.requestStartIfIdle(() -> null).queued());
        assertTrue(session.claimStart(recovered));
        assertFalse(session.claimStart(recovered));
        assertTrue(session.retainService(recovered, true));
        assertEquals(NativeHomeStatus.Phase.STARTING, NativeHomeStatus.phase(
                true, session.starting(), session.accepting(), false, "pending"));
        assertTrue(session.activate(recovered));
        assertEquals(NativeHomeStatus.Phase.RUNNING, NativeHomeStatus.phase(
                true, session.starting(), session.accepting(), true, "none"));
    }

    @Test public void duplicateStartDoesNotInvalidateRecoveredHomeOrStickyRetention() {
        long recovered = recover();
        assertTrue(session.claimStart(recovered));
        assertTrue(session.activate(recovered));
        long home = session.homeSignal();
        assertFalse(session.requestStartIfIdle(() -> null).queued());
        assertTrue(session.current(home));
        assertTrue(session.retainService(recovered, true));
        AtomicInteger dispatches = new AtomicInteger();
        assertTrue(session.dispatch(home, () -> true, dispatches::incrementAndGet));
        assertEquals(1, dispatches.get());
    }

    @Test public void destructionBeforeRecoveryWorkerCreationReleasesReservation() {
        long recovered = recover();
        assertTrue(session.claimStart(recovered));
        session.serviceDestroyed(recovered);
        assertFalse(session.activeOrStarting());
        assertFalse(session.retainService(recovered, true));
        assertEquals(-1, session.requestProcessRecovery(() -> true, () -> true, () -> true));
        assertTrue(session.requestStartIfIdle(() -> null).queued());
    }

    @Test public void oldRecoveryDestructionDoesNotClearNewSubmittedStart() {
        long old = recover();
        assertTrue(session.claimStart(old));
        assertTrue(session.stop(() -> true));
        long fresh = session.requestStartIfIdle(() -> null).ticket;
        session.startSubmitted(fresh);
        session.serviceDestroyed(old);
        assertTrue(session.startCurrent(fresh));
        assertTrue(session.retainService(old, true));
        assertTrue(session.claimStart(fresh));
    }

    @Test public void staleRecoveryFailureCannotStopNewStartOrOverwriteDurableStop() {
        long old = recover();
        assertTrue(session.claimStart(old));
        assertTrue(session.stop(() -> true));
        assertFalse(session.duringStart(old, () -> fail("Old failure overwrote Stop")));
        assertTrue(session.stopDurable());
        long fresh = session.requestStartIfIdle(() -> null).ticket;
        session.startSubmitted(fresh);
        assertFalse(session.duringStart(old, () -> session.stop(() -> false)));
        assertTrue(session.startCurrent(fresh));
        assertTrue(session.retainService(old, true));
        assertEquals("uncertain", session.stopError());
    }

    @Test public void staleRecoveredHomeFailureCannotCancelNewHomeGeneration() {
        long recovered = recover();
        assertTrue(session.claimStart(recovered));
        assertTrue(session.activate(recovered));
        long oldHome = session.homeSignal(), newHome = session.homeSignal();
        assertFalse(session.duringCurrent(oldHome, () -> session.stop(() -> true)));
        assertTrue(session.current(newHome));
        assertTrue(session.retainService(recovered, true));
    }

    @Test public void stopAndNewStartDuringNormalizationRetainOnlyNewSubmittedWork() {
        long old = recover();
        assertTrue(session.claimStart(old));
        AtomicLong fresh = new AtomicLong(-1);
        assertFalse(session.duringStart(old, true, () -> true, () -> {
            session.stop(() -> true);
            fresh.set(session.requestStartIfIdle(() -> null).ticket);
            session.startSubmitted(fresh.get());
        }, () -> fail("Stale recovery must not enter foreground")));
        assertTrue(session.startCurrent(fresh.get()));
        assertTrue(session.retainService(old, true));
        session.serviceDestroyed(old);
        assertTrue(session.claimStart(fresh.get()));
    }

    @Test public void explicitStopClearsSubmittedRecoveryWorkAndBlocksStickyRetention() {
        long recovered = recover();
        assertTrue(session.claimStart(recovered));
        session.startSubmitted(recovered);
        assertTrue(session.retainService(recovered, true));
        assertTrue(session.stop(() -> true));
        assertFalse(session.retainService(recovered, true));
        assertFalse(session.retainService(recovered, false));
        assertFalse(session.claimStart(recovered));
        assertFalse(session.duringStart(recovered, () -> fail("Stopped recovery ran")));
        assertTrue(session.stopDurable());
    }

    @Test public void refusedRecoveryDoesNotChangePausedStatusOrCreateServiceWork() {
        assertEquals(-1, session.requestProcessRecovery(() -> true, () -> false,
                () -> { fail("No budget consumption after refusal"); return true; }));
        assertFalse(session.activeOrStarting());
        assertFalse(session.retainService(-1, true));
        assertEquals(NativeHomeStatus.Phase.PAUSED, NativeHomeStatus.phase(
                true, session.starting(), session.accepting(), false, "stopped"));
    }

    @Test public void matchingRecoveryFailureClearsRetentionEvenWhenSavedOffCommitFails() {
        long recovered = recover();
        assertTrue(session.claimStart(recovered));
        assertTrue(session.duringStart(recovered, () -> session.stop(() -> false)));
        // Failed persistence may leave a saved On bit; this process must still reject restart.
        assertFalse(session.retainService(recovered, true));
        assertFalse(session.activeOrStarting());
        assertFalse(session.stopDurable());
        assertEquals("commit_failed", session.stopError());
        assertEquals(-1, session.requestProcessRecovery(() -> true, () -> true, () -> true));
    }
}
