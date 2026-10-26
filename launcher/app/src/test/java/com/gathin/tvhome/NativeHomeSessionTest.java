package com.gathin.tvhome;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NativeHomeSessionTest {
    @Test public void stopPreventsEveryPendingCalibrationForegroundRefresh() {
        NativeHomeSession state = new NativeHomeSession();
        long ticket = state.requestStart(); AtomicInteger foregroundCalls = new AtomicInteger();
        assertTrue(state.duringStart(ticket, foregroundCalls::incrementAndGet));
        state.stop(() -> true);
        assertFalse(state.duringStart(ticket, foregroundCalls::incrementAndGet));
        assertEquals(1, foregroundCalls.get()); assertFalse(state.activate(ticket));
    }
    @Test public void supersededStartCannotRefreshForeground() {
        NativeHomeSession state = new NativeHomeSession();
        long old = state.requestStart(); state.requestStart();
        assertFalse(state.duringStart(old, () -> fail("Old calibration executed")));
    }
    @Test public void readinessBecomesFalseSynchronouslyAtStopOrNewStart() {
        NativeHomeSession state = new NativeHomeSession(); long ticket = state.requestStart();
        assertFalse(state.accepting()); assertTrue(state.activate(ticket)); assertTrue(state.accepting());
        state.stop(() -> false); assertFalse(state.accepting());
        ticket = state.requestStart(); assertTrue(state.activate(ticket));
        state.requestStart(); assertFalse(state.accepting());
    }
    @Test(timeout = 5000) public void stopWaitsForInFlightForegroundRefreshThenBlocksAllLaterOnes() throws Exception {
        NativeHomeSession state = new NativeHomeSession(); long ticket = state.requestStart();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch stopBegun = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Thread refresh = new Thread(() -> state.duringStart(ticket, () -> {
            entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            calls.incrementAndGet();
        }));
        Thread stop = new Thread(() -> { stopBegun.countDown(); state.stop(() -> true); stopped.countDown(); });
        refresh.start(); assertTrue(entered.await(1, TimeUnit.SECONDS)); stop.start();
        try {
            assertTrue(stopBegun.await(1, TimeUnit.SECONDS));
            assertFalse(stopped.await(50, TimeUnit.MILLISECONDS));
        } finally { release.countDown(); }
        refresh.join(2000); stop.join(2000);
        assertEquals(0, stopped.getCount()); assertEquals(1, calls.get());
        assertFalse(state.duringStart(ticket, calls::incrementAndGet)); assertEquals(1, calls.get());
    }
    private final NativeHomeSession session = new NativeHomeSession();
    private long signal() {
        long ticket = session.requestStart(); assertTrue(session.activate(ticket));
        return session.homeSignal();
    }
    @Test public void coldProcessDoesNotClaimDurableStop() {
        assertFalse(session.stopDurable()); assertEquals("uncertain", session.stopError());
    }
    @Test public void successfulStopConfirmsPersistenceAndInvalidatesPendingHome() {
        long token = signal(); AtomicBoolean sent = new AtomicBoolean();
        assertTrue(session.stop(() -> true));
        assertFalse(session.dispatch(token, () -> true, () -> sent.set(true)));
        assertFalse(sent.get()); assertTrue(session.stopDurable()); assertEquals("none", session.stopError());
    }
    @Test public void failedCommitCancelsRuntimeButDoesNotClaimDurableOptOut() {
        long token = signal(); AtomicBoolean inMemoryEnabled = new AtomicBoolean(true);
        assertFalse(session.stop(() -> { inMemoryEnabled.set(false); return false; }));
        assertFalse(inMemoryEnabled.get()); assertFalse(session.current(token));
        assertFalse(session.stopDurable()); assertEquals("commit_failed", session.stopError());
        assertFalse(session.dispatch(token, () -> true, () -> fail("No dispatch after failed Stop save")));
    }
    @Test public void commitExceptionIsAnExplicitFailure() {
        long token = signal();
        assertFalse(session.stop(() -> { throw new IllegalStateException("disk error"); }));
        assertFalse(session.current(token)); assertEquals("commit_failed", session.stopError());
    }
    @Test public void runtimeIsInvalidatedBeforeCommitExecutes() {
        long token = signal();
        assertTrue(session.stop(() -> { assertFalse(session.current(token)); return true; }));
    }
    @Test public void stopInvalidatesStartIntentAlreadyQueued() {
        long ticket = session.requestStart(); assertTrue(session.startCurrent(ticket));
        session.stop(() -> true);
        assertFalse(session.startCurrent(ticket)); assertFalse(session.activate(ticket));
        assertEquals(-1, session.homeSignal());
    }
    @Test public void oldQueuedStartCannotActivateAfterNewStartRequest() {
        long old = session.requestStart(); session.stop(() -> true);
        long fresh = session.requestStart();
        assertFalse(session.activate(old)); assertTrue(session.activate(fresh));
    }
    @Test public void aNewStartInvalidatesPreviousDurabilityProof() {
        session.stop(() -> true); session.requestStart();
        assertFalse(session.stopDurable()); assertEquals("uncertain", session.stopError());
    }
    @Test public void successfulRetryClearsCommitFailure() {
        session.stop(() -> false); assertTrue(session.stop(() -> true));
        assertTrue(session.stopDurable()); assertEquals("none", session.stopError());
    }
    @Test public void newHomeSupersedesOlderGeneration() {
        long old = signal(), newer = session.homeSignal();
        assertFalse(session.current(old)); assertTrue(session.current(newer));
    }
    @Test public void finalReadinessFailureDoesNotSend() {
        long token = signal();
        assertFalse(session.dispatch(token, () -> false, () -> fail("No send when not ready")));
    }
    @Test public void alreadySentBroadcastCannotBeRecalled() {
        long token = signal(); AtomicInteger sends = new AtomicInteger();
        assertTrue(session.dispatch(token, () -> true, sends::incrementAndGet));
        session.stop(() -> true); assertEquals(1, sends.get());
        assertFalse(session.dispatch(token, () -> true, sends::incrementAndGet));
    }
    @Test public void oldServiceDestructionDoesNotInvalidateNewQueuedStart() {
        long old = session.requestStart(); session.activate(old);
        session.stop(() -> true); long newer = session.requestStart();
        session.serviceDestroyed(old); assertTrue(session.activate(newer));
    }
    @Test public void currentServiceDestructionStopsCurrentGeneration() {
        long ticket = session.requestStart(); session.activate(ticket);
        long token = session.homeSignal(); session.serviceDestroyed(ticket);
        assertFalse(session.current(token));
    }
    @Test(timeout = 5000) public void dispatchCannotPassTheBarrierWhileStopCommits() throws Exception {
        long token = signal(); AtomicBoolean sent = new AtomicBoolean();
        CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread stop = new Thread(() -> session.stop(() -> {
            committing.countDown();
            try { return release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }));
        stop.start(); assertTrue(committing.await(1, TimeUnit.SECONDS));
        Thread send = new Thread(() -> session.dispatch(token, () -> true, () -> sent.set(true)));
        send.start(); release.countDown(); stop.join(2000); send.join(2000);
        assertFalse(stop.isAlive()); assertFalse(send.isAlive()); assertFalse(sent.get());
        assertTrue(session.stopDurable());
    }
}
