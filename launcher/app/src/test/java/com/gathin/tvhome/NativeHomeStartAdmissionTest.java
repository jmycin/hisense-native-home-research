package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Exercises the same reservation/delivery seam used by Access and onStartCommand, without Android. */
public class NativeHomeStartAdmissionTest {
    private final NativeHomeSession session = new NativeHomeSession();
    private final List<Long> queued = new ArrayList<>();

    private NativeHomeSession.StartRequest start() {
        NativeHomeSession.StartRequest request = session.requestStartIfIdle(() -> null);
        if (request.queued()) queued.add(request.ticket);
        return request;
    }

    @Test public void repeatedStartBeforeServiceDeliveryQueuesOnlyOneCommand() {
        long original = start().ticket;
        assertFalse(start().queued());
        assertEquals(1, queued.size());
        assertTrue(session.startCurrent(original));
        assertTrue(session.claimStart(original));
        assertTrue(session.activate(original));
    }

    @Test public void repeatedStartDuringCalibrationPreservesTicketAndOneInitialization() {
        long original = start().ticket;
        AtomicInteger initializations = new AtomicInteger();
        if (session.claimStart(original)) initializations.incrementAndGet();
        assertFalse(start().queued());
        if (session.claimStart(original)) initializations.incrementAndGet();
        assertEquals(1, queued.size());
        assertEquals(1, initializations.get());
        assertTrue(session.startCurrent(original));
        assertTrue(session.activate(original));
    }

    @Test public void repeatedStartWhenRunningPreservesInFlightHomeGeneration() {
        long original = start().ticket;
        assertTrue(session.claimStart(original));
        assertTrue(session.activate(original));
        long home = session.homeSignal();
        assertFalse(start().queued());
        assertEquals(1, queued.size());
        assertTrue(session.current(home));
        AtomicInteger sends = new AtomicInteger();
        assertTrue(session.dispatch(home, () -> true, sends::incrementAndGet));
        assertEquals(1, sends.get());
    }

    @Test public void refusedPrerequisiteDoesNotReserveOrInvalidateDurableStop() {
        assertTrue(session.stop(() -> true));
        NativeHomeSession.StartRequest denied = session.requestStartIfIdle(() -> "permission_missing");
        assertFalse(denied.queued());
        assertEquals("permission_missing", denied.problem);
        assertFalse(session.activeOrStarting());
        assertTrue(session.stopDurable());
        assertTrue(start().queued());
    }

    @Test public void prerequisiteExceptionLeavesNoReservation() {
        try {
            session.requestStartIfIdle(() -> { throw new IllegalStateException("unavailable"); });
            fail("Expected prerequisite failure");
        } catch (IllegalStateException expected) {
            assertFalse(session.activeOrStarting());
            assertTrue(start().queued());
        }
    }

    @Test public void refusedDuplicateDoesNotReplaceAnExistingCalibration() {
        long ticket = start().ticket;
        assertTrue(session.claimStart(ticket));
        NativeHomeSession.StartRequest denied = session.requestStartIfIdle(() -> "access_revoked");
        assertEquals("access_revoked", denied.problem);
        assertFalse(denied.queued());
        assertTrue(session.startCurrent(ticket));
    }

    @Test public void stopBeforeDeliveryRejectsQueuedCommandAndAllowsFreshStart() {
        long old = start().ticket;
        assertTrue(session.stop(() -> true));
        assertFalse(session.claimStart(old));
        assertFalse(session.activate(old));
        long fresh = start().ticket;
        assertNotEquals(old, fresh);
        assertTrue(session.claimStart(fresh));
        assertTrue(session.activate(fresh));
    }

    @Test public void pendingServiceDestructionReleasesReservationForRetry() {
        long old = start().ticket;
        assertTrue(session.claimStart(old));
        session.serviceDestroyed(old);
        assertFalse(session.activeOrStarting());
        assertFalse(session.activate(old));
        assertTrue(start().queued());
    }

    @Test public void oldServiceDestructionCannotClearNewPendingStart() {
        long old = start().ticket;
        assertTrue(session.claimStart(old));
        session.stop(() -> true);
        long fresh = start().ticket;
        session.serviceDestroyed(old);
        assertTrue(session.activeOrStarting());
        assertTrue(session.startCurrent(fresh));
        assertTrue(session.claimStart(fresh));
    }

    @Test public void staleDeliveryCannotClaimOrReplaceNewPendingOwner() {
        long old = start().ticket;
        session.stop(() -> true);
        long fresh = start().ticket;
        assertFalse(session.claimStart(old));
        assertTrue(session.activeOrStarting());
        assertTrue(session.claimStart(fresh));
    }

    @Test public void lateCalibrationFailureCannotStopFreshStartOrOverwriteItsStatus() {
        long old = start().ticket;
        session.claimStart(old);
        session.stop(() -> true);
        long fresh = start().ticket;
        AtomicInteger failures = new AtomicInteger();
        assertFalse(session.duringStart(old, () -> {
            failures.incrementAndGet(); session.stop(() -> true);
        }));
        assertEquals(0, failures.get());
        assertTrue(session.startCurrent(fresh));
        assertTrue(session.claimStart(fresh));
    }

    @Test public void matchingCalibrationFailureStopsInsideTheOwnershipBarrier() {
        long ticket = start().ticket;
        session.claimStart(ticket);
        AtomicInteger saved = new AtomicInteger();
        assertTrue(session.duringStart(ticket, () -> session.stop(() -> {
            saved.incrementAndGet(); return true;
        })));
        assertEquals(1, saved.get());
        assertFalse(session.activate(ticket));
        assertTrue(session.stopDurable());
        assertTrue(start().queued());
    }

    @Test public void lateHomeFailureCannotCancelFreshStart() {
        long old = start().ticket;
        session.claimStart(old); session.activate(old);
        long home = session.homeSignal();
        session.stop(() -> true);
        long fresh = start().ticket;
        assertFalse(session.duringCurrent(home, () -> session.stop(() -> true)));
        assertTrue(session.startCurrent(fresh));
    }

    @Test public void lateHomeFailureCannotCancelANewerHomeGeneration() {
        long ticket = start().ticket;
        session.claimStart(ticket); session.activate(ticket);
        long oldHome = session.homeSignal(), newHome = session.homeSignal();
        assertFalse(session.duringCurrent(oldHome, () -> session.stop(() -> true)));
        AtomicInteger sends = new AtomicInteger();
        assertTrue(session.dispatch(newHome, () -> true, sends::incrementAndGet));
        assertEquals(1, sends.get());
    }

    @Test public void currentHomeFailureStopsAndCancelsPendingDispatch() {
        long ticket = start().ticket;
        session.claimStart(ticket); session.activate(ticket);
        long home = session.homeSignal();
        assertTrue(session.duringCurrent(home, () -> session.stop(() -> true)));
        assertFalse(session.dispatch(home, () -> true, () -> fail("Stopped Home dispatched")));
        assertTrue(session.stopDurable());
    }

    @Test(timeout = 5000) public void concurrentStartRequestsAdmitOneCommand() throws Exception {
        CountDownLatch ready = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread caller = new Thread(() -> {
                ready.countDown();
                try { release.await(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); return; }
                if (session.requestStartIfIdle(() -> null).queued()) admitted.incrementAndGet();
            });
            callers.add(caller); caller.start();
        }
        ready.await(); release.countDown();
        for (Thread caller : callers) caller.join(2000);
        assertEquals(1, admitted.get());
        assertTrue(session.starting());
    }
}
