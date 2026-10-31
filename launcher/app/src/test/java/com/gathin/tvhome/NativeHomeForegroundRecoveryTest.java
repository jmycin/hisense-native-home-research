package com.gathin.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class NativeHomeForegroundRecoveryTest {
    @Test public void ordinaryAndBootStartDoNotPulseForeground() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        List<String> actions=new ArrayList<>();
        assertTrue(session.duringStart(ticket,false,()->true,
                ()->actions.add("normalize"),()->actions.add("start")));
        assertEquals(Arrays.asList("start"),actions);
    }
    @Test public void admittedNullRecoveryNormalizesBeforeOneForegroundStart() {
        NativeHomeSession session=new NativeHomeSession();
        long ticket=session.requestProcessRecovery(()->true,()->true,()->true);
        List<String> actions=new ArrayList<>();
        assertTrue(session.duringStart(ticket,true,()->true,
                ()->actions.add("normalize"),()->actions.add("start")));
        assertEquals(Arrays.asList("normalize","start"),actions);
    }
    @Test public void savedOptOutBlocksBothActions() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        assertFalse(session.duringStart(ticket,true,()->false,
                ()->fail("No normalization when opted out"),()->fail("No start when opted out")));
    }
    @Test public void stopBeforeForegroundBlocksBothActions() {
        NativeHomeSession session=new NativeHomeSession();
        long ticket=session.requestProcessRecovery(()->true,()->true,()->true);
        session.stop(()->true);
        assertFalse(session.duringStart(ticket,true,()->true,
                ()->fail("No normalization after Stop"),()->fail("No start after Stop")));
    }
    @Test public void supersededTicketCannotNormalizeOrStart() {
        NativeHomeSession session=new NativeHomeSession();long old=session.requestStart();
        long fresh=session.requestStart();
        assertFalse(session.duringStart(old,true,()->true,
                ()->fail("No old normalization"),()->fail("No old start")));
        assertTrue(session.startCurrent(fresh));
    }
    @Test public void reentrantStopDuringNormalizationBlocksForegroundRestart() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        AtomicBoolean optedIn=new AtomicBoolean(true);
        assertFalse(session.duringStart(ticket,true,optedIn::get,
                ()->session.stop(()->{optedIn.set(false);return true;}),
                ()->fail("Stop must win after normalization")));
        assertTrue(session.stopDurable());assertFalse(session.startCurrent(ticket));
        assertFalse(session.retainService(ticket,false));assertFalse(session.retainService(ticket,true));
    }
    @Test public void reentrantNewStartDuringNormalizationKeepsNewerTicket() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        AtomicLong newer=new AtomicLong(-1);
        assertFalse(session.duringStart(ticket,true,()->true,
                ()->{newer.set(session.requestStart());session.startSubmitted(newer.get());},
                ()->fail("No stale foreground start")));
        assertTrue(session.startCurrent(newer.get()));assertTrue(session.hasServiceWork(-1));
        assertTrue(session.retainService(ticket,true));assertFalse(session.retainService(ticket,false));
    }
    @Test public void lostOptInDuringNormalizationBlocksStartEvenWithoutTicketChange() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        AtomicBoolean optedIn=new AtomicBoolean(true);
        assertFalse(session.duringStart(ticket,true,optedIn::get,
                ()->optedIn.set(false),()->fail("No start with changed saved choice")));
    }
    @Test public void normalizationFailureDoesNotProceedToStart() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        try {
            session.duringStart(ticket,true,()->true,
                    ()->{throw new IllegalStateException("Unavailable framework");},
                    ()->fail("No start after failed normalization"));
            fail("Failure must reach the service's existing fail-closed handler");
        } catch (IllegalStateException expected) { assertEquals("Unavailable framework",expected.getMessage()); }
    }
    @Test public void reentrantStopInFirstOptInReadBlocksNormalization() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        assertFalse(session.duringStart(ticket,true,()->{session.stop(()->true);return true;},
                ()->fail("No normalization after reentrant Stop"),()->fail("No start after Stop")));
    }
    @Test public void reentrantStopInSecondOptInReadBlocksForegroundStart() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        int[] reads={0};List<String> actions=new ArrayList<>();
        assertFalse(session.duringStart(ticket,true,()->{
            if (++reads[0]==2) session.stop(()->false);return true;
        },()->actions.add("normalize"),()->fail("No start after reentrant Stop")));
        assertEquals(Arrays.asList("normalize"),actions);assertFalse(session.stopDurable());
    }
    @Test public void reentrantStopDuringStartIsNotReportedAsSuccessfulStartup() {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        assertFalse(session.duringStart(ticket,true,()->true,()->{},()->session.stop(()->true)));
        assertFalse(session.startCurrent(ticket));assertTrue(session.stopDurable());
    }
    @Test(timeout=5000) public void concurrentStopSharesTheWholeNormalizeStartBarrier() throws Exception {
        NativeHomeSession session=new NativeHomeSession();long ticket=session.requestStart();
        CountDownLatch normalized=new CountDownLatch(1),release=new CountDownLatch(1),stopDone=new CountDownLatch(1);
        List<String> actions=new ArrayList<>();
        Thread startup=new Thread(()->session.duringStart(ticket,true,()->true,()->{
            actions.add("normalize");normalized.countDown();
            try { release.await(2,TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        },()->actions.add("start")));
        startup.start();assertTrue(normalized.await(1,TimeUnit.SECONDS));
        Thread stopping=new Thread(()->{session.stop(()->true);stopDone.countDown();});stopping.start();
        try { assertFalse(stopDone.await(50,TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); }
        startup.join(2000);stopping.join(2000);
        assertFalse(startup.isAlive());assertFalse(stopping.isAlive());assertEquals(0,stopDone.getCount());
        assertEquals(Arrays.asList("normalize","start"),actions);
        assertFalse(session.startCurrent(ticket));assertTrue(session.stopDurable());
    }
}
