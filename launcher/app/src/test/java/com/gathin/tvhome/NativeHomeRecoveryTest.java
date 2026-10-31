package com.gathin.tvhome;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class NativeHomeRecoveryTest {
    private static final class Store implements NativeHomeRecoveryBudget.Store {
        NativeHomeRecoveryBudget.State value;
        boolean commit = true;
        int writes;
        @Override public NativeHomeRecoveryBudget.State read() { return value; }
        @Override public boolean write(NativeHomeRecoveryBudget.State next) {
            writes++;
            if (commit) value = next;
            return commit;
        }
    }
    private long recover(NativeHomeSession session, Store store, int boot, long elapsed) {
        return session.requestProcessRecovery(() -> true, () -> true,
                () -> NativeHomeRecoveryBudget.claim(store, boot, elapsed));
    }
    @Test public void optedOutNeverChecksPrerequisitesOrConsumesBudget() {
        NativeHomeSession session = new NativeHomeSession();
        assertEquals(-1, session.requestProcessRecovery(() -> false,
                () -> { fail("No prerequisite work after opt-out"); return true; },
                () -> { fail("No budget write after opt-out"); return true; }));
    }
    @Test public void failedPrerequisitesNeverConsumeBudget() {
        NativeHomeSession session = new NativeHomeSession();
        assertEquals(-1, session.requestProcessRecovery(() -> true, () -> false,
                () -> { fail("No budget write after failed guards"); return true; }));
    }
    @Test public void budgetCommitsBeforeTicketAndRecoveryDoesNotWriteOptIn() {
        NativeHomeSession session = new NativeHomeSession();
        AtomicBoolean optedIn = new AtomicBoolean(true);
        long ticket = session.requestProcessRecovery(optedIn::get, () -> true, () -> {
            assertFalse(session.accepting()); assertFalse(session.startCurrent(1)); return true;
        });
        assertTrue(ticket > 0); assertTrue(session.startCurrent(ticket)); assertTrue(optedIn.get());
        assertTrue(session.activate(ticket));
    }
    @Test public void successfulOrFailedStopVetoesSameProcessRecoveryEvenIfSavedOn() {
        for (boolean saved : new boolean[]{true, false}) {
            NativeHomeSession session = new NativeHomeSession(); session.stop(() -> saved);
            assertEquals(-1, session.requestProcessRecovery(() -> true, () -> true, () -> true));
        }
    }
    @Test public void oneNullAdmissionPerProcessAndManualStartIsNotReplaced() {
        NativeHomeSession session = new NativeHomeSession(); Store store = new Store();
        long ticket = recover(session, store, 9, 100);
        assertTrue(ticket > 0);
        assertEquals(-1, recover(session, store, 9, 101)); assertEquals(1, store.writes);
        NativeHomeSession manual = new NativeHomeSession(); long pending = manual.requestStart();
        manual.startSubmitted(pending);
        assertEquals(-1, recover(manual, store, 9, 102));
        assertTrue(manual.startCurrent(pending)); assertTrue(manual.hasServiceWork(-1));
    }
    @Test public void acceptedCalibrationAndActiveWorkSurviveStaleDeliveryDisposition() {
        NativeHomeSession session = new NativeHomeSession(); long ticket = session.requestStart();
        assertFalse(session.hasServiceWork(-1)); // reservation is not a queued service delivery
        session.startSubmitted(ticket); assertTrue(session.hasServiceWork(-1));
        assertTrue(session.hasServiceWork(ticket)); assertTrue(session.activate(ticket));
        assertTrue(session.hasServiceWork(ticket));
        session.stop(() -> true); assertFalse(session.hasServiceWork(ticket));
    }
    @Test public void reentrantStopDuringAnyAdmissionCallbackWins() {
        for (int phase=0; phase<3; phase++) {
            NativeHomeSession session = new NativeHomeSession(); final int at = phase;
            long ticket = session.requestProcessRecovery(
                    () -> { if (at==0) session.stop(() -> true); return true; },
                    () -> { if (at==1) session.stop(() -> false); return true; },
                    () -> { if (at==2) session.stop(() -> true); return true; });
            assertEquals(-1, ticket); assertFalse(session.accepting());
        }
    }
    @Test public void reentrantNewManualStartDuringBudgetCommitWins() {
        NativeHomeSession session = new NativeHomeSession(); AtomicLong newer = new AtomicLong();
        assertEquals(-1, session.requestProcessRecovery(() -> true, () -> true, () -> {
            newer.set(session.requestStart()); session.startSubmitted(newer.get()); return true;
        }));
        assertTrue(session.startCurrent(newer.get())); assertTrue(session.hasServiceWork(-1));
    }
    @Test(timeout=5000) public void concurrentStopWaitsForAdmissionThenInvalidatesItsTicket() throws Exception {
        NativeHomeSession session = new NativeHomeSession();
        CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicLong admitted = new AtomicLong(-1);
        Thread recovering = new Thread(() -> admitted.set(session.requestProcessRecovery(
                () -> true, () -> true, () -> {
                    committing.countDown();
                    try { return release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                })));
        recovering.start(); assertTrue(committing.await(1, TimeUnit.SECONDS));
        Thread stopping = new Thread(() -> session.stop(() -> true)); stopping.start();
        release.countDown(); recovering.join(2000); stopping.join(2000);
        assertFalse(recovering.isAlive()); assertFalse(stopping.isAlive());
        assertTrue(admitted.get() > 0); assertFalse(session.startCurrent(admitted.get()));
        assertFalse(session.activate(admitted.get()));
    }
    @Test public void twoAdmissionsAcrossFreshProcessesThenCrashLoopStops() {
        Store store = new Store();
        assertTrue(recover(new NativeHomeSession(),store,8,100)>0);
        assertTrue(recover(new NativeHomeSession(),store,8,101)>0);
        assertEquals(-1,recover(new NativeHomeSession(),store,8,102));
        assertEquals(2,store.value.attempts); assertEquals(2,store.writes);
    }
    @Test public void expiredWindowAllowsOccasionalKillsWithoutLifetimeLimit() {
        Store store = new Store();
        assertTrue(recover(new NativeHomeSession(),store,8,100)>0);
        assertTrue(recover(new NativeHomeSession(),store,8,101)>0);
        assertTrue(recover(new NativeHomeSession(),store,8,100+NativeHomeRecoveryBudget.WINDOW_MS)>0);
        assertEquals(1,store.value.attempts);
        assertEquals(100+NativeHomeRecoveryBudget.WINDOW_MS,store.value.beganElapsed);
    }
    @Test public void newBootAllowsNewWindowButFutureBootAndElapsedRollbackFailClosed() {
        Store store = new Store();
        store.value=new NativeHomeRecoveryBudget.State(8,2,500);
        assertFalse(NativeHomeRecoveryBudget.claim(store,7,800));
        assertFalse(NativeHomeRecoveryBudget.claim(store,8,499));
        assertTrue(NativeHomeRecoveryBudget.claim(store,9,1));
        assertEquals(9,store.value.boot); assertEquals(1,store.value.attempts);
    }
    @Test public void malformedCountersAndUnavailableClockFailClosedWithoutWrite() {
        Store store = new Store();
        for (NativeHomeRecoveryBudget.State bad : new NativeHomeRecoveryBudget.State[]{
                new NativeHomeRecoveryBudget.State(-1,0,0),
                new NativeHomeRecoveryBudget.State(8,-1,0),
                new NativeHomeRecoveryBudget.State(8,3,0),
                new NativeHomeRecoveryBudget.State(8,0,-1)}) {
            store.value=bad; assertFalse(NativeHomeRecoveryBudget.claim(store,8,100));
        }
        store.value=null;
        assertFalse(NativeHomeRecoveryBudget.claim(store,-1,100));
        assertFalse(NativeHomeRecoveryBudget.claim(store,8,-1)); assertEquals(0,store.writes);
    }
    @Test public void failedOrThrowingBudgetDoesNotAdmitAndDoesNotRetryInSameProcess() {
        NativeHomeSession session = new NativeHomeSession(); Store store = new Store(); store.commit=false;
        assertEquals(-1,recover(session,store,8,100)); store.commit=true;
        assertEquals(-1,recover(session,store,8,101)); assertEquals(1,store.writes);
        NativeHomeSession throwing = new NativeHomeSession();
        assertEquals(-1,throwing.requestProcessRecovery(() -> true,() -> true,() -> {
            throw new IllegalStateException("unreadable budget");
        }));
    }
    @Test public void explicitUserResetRestoresBudgetButAutomaticClaimNeverResetsIt() {
        Store store = new Store(); store.value=new NativeHomeRecoveryBudget.State(8,2,100);
        assertFalse(NativeHomeRecoveryBudget.claim(store,8,200));
        assertTrue(NativeHomeRecoveryBudget.resetForUserStart(store,8,201));
        assertEquals(0,store.value.attempts);
        assertTrue(NativeHomeRecoveryBudget.claim(store,8,202)); assertEquals(1,store.value.attempts);
    }
}
