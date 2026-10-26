package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static com.gathin.tvhome.NativeHomePolicy.Decision.*;
import static org.junit.Assert.*;

public class NativeHomePolicyTest {
    private static final long WALL = 100_000_000L, ELAPSED = WALL;
    private final NativeHomePolicy policy = new NativeHomePolicy();
    private final NativeHomePolicy.Foreground foreground = new NativeHomePolicy.Foreground();

    private NativeHomePolicy.Event home(long time, int type) {
        return new NativeHomePolicy.Event(time, type, NativeHomePolicy.STOCK_PACKAGE,
                NativeHomePolicy.STOCK_HOME);
    }
    private void begin() { policy.begin(1, ELAPSED, WALL); }
    private void seedHome() { foreground.accept(home(WALL - 100, 1)); }
    private NativeHomePolicy.Decision sample(long age, NativeHomePolicy.Event... events) {
        for (NativeHomePolicy.Event event : events) foreground.accept(event);
        return policy.sample(1, ELAPSED + age, WALL + age, true, foreground, Arrays.asList(events));
    }

    @Test public void recentPresignalResumeNeedsTwoStableObservations() {
        seedHome(); begin();
        assertEquals(WAIT, sample(0));
        assertEquals(WAIT, sample(99));
        assertEquals(SEND, sample(100));
    }
    @Test public void freshTransitionFromAppIsSupported() {
        foreground.accept(new NativeHomePolicy.Event(WALL - 100, 1, "app", "app.Screen"));
        begin();
        assertEquals(WAIT, sample(0));
        assertEquals(WAIT, sample(80, new NativeHomePolicy.Event(WALL + 10, 2, "app", "app.Screen"),
                home(WALL + 60, 1)));
        assertEquals(SEND, sample(180));
    }
    @Test public void matchingPackageButInputPanelCancels() {
        seedHome(); begin(); assertEquals(WAIT, sample(0));
        assertEquals(CANCEL, sample(100, new NativeHomePolicy.Event(WALL + 50, 1,
                NativeHomePolicy.STOCK_PACKAGE, NativeHomePolicy.STOCK_PACKAGE + ".inputs.InputsPanelActivity")));
    }
    @Test public void settingsAfterHomeCancelsEvenIfHomeResumesAgain() {
        seedHome(); begin(); assertEquals(WAIT, sample(0));
        assertEquals(CANCEL, sample(100, new NativeHomePolicy.Event(WALL + 20, 1,
                "com.android.tv.settings", "Settings"), home(WALL + 80, 1)));
    }
    @Test public void anotherAppAfterSignalCancels() {
        begin();
        assertEquals(CANCEL, sample(10, new NativeHomePolicy.Event(WALL + 5, 1, "app", "Screen")));
    }
    @Test public void exactClassInWrongPackageDoesNotMatch() {
        foreground.accept(new NativeHomePolicy.Event(WALL - 1, 1, "another", NativeHomePolicy.STOCK_HOME));
        begin(); assertEquals(WAIT, sample(0)); assertEquals(WAIT, sample(100));
    }
    @Test public void classPrefixDoesNotMatch() {
        foreground.accept(new NativeHomePolicy.Event(WALL - 1, 1, NativeHomePolicy.STOCK_PACKAGE,
                NativeHomePolicy.STOCK_HOME + "$Panel"));
        begin(); assertEquals(WAIT, sample(100));
    }
    @Test public void pauseAfterSignalCancels() {
        seedHome(); begin(); assertEquals(WAIT, sample(0));
        assertEquals(CANCEL, sample(100, home(WALL + 50, 2)));
    }
    @Test public void stopAfterSignalCancels() {
        seedHome(); begin(); assertEquals(WAIT, sample(0));
        assertEquals(CANCEL, sample(100, home(WALL + 50, 23)));
    }
    @Test public void seededPausedHomeIsNotForeground() {
        seedHome(); foreground.accept(home(WALL - 10, 2)); begin();
        assertEquals(WAIT, sample(0)); assertEquals(WAIT, sample(100));
    }
    @Test public void seedHistorySelectsLatestResumedActivity() {
        seedHome(); foreground.accept(new NativeHomePolicy.Event(WALL - 10, 1, "app", "Screen"));
        begin(); assertEquals(WAIT, sample(100));
    }
    @Test public void noHistoryLeavesStockAloneAtExpiry() {
        begin(); assertEquals(WAIT, sample(0)); assertEquals(CANCEL, sample(2000));
    }
    @Test public void fiveSecondOldHomeWithoutNewResumeIsInsufficient() {
        foreground.accept(home(WALL - 5000, 1)); begin();
        assertEquals(WAIT, sample(0)); assertEquals(WAIT, sample(100));
        assertEquals(CANCEL, sample(2000));
    }
    @Test public void dayOldHomeCannotEstablishReadiness() {
        foreground.accept(home(WALL - 86400000, 1)); begin();
        assertEquals(WAIT, sample(0)); assertEquals(CANCEL, sample(2000));
    }
    @Test public void presignalToleranceIsInclusiveAt250Milliseconds() {
        foreground.accept(home(WALL - 250, 1)); begin();
        assertEquals(WAIT, sample(0)); assertEquals(SEND, sample(100));
    }
    @Test public void presignalResume251MillisecondsAgoDoesNotQualify() {
        foreground.accept(home(WALL - 251, 1)); begin();
        assertEquals(WAIT, sample(0)); assertEquals(WAIT, sample(100));
        assertEquals(CANCEL, sample(2000));
    }
    @Test public void anotherWindowMustStartWithClearedForeground() {
        seedHome(); begin(); assertEquals(WAIT, sample(0)); assertEquals(SEND, sample(100));
        foreground.clear(); policy.begin(2, ELAPSED + 500, WALL + 500);
        assertEquals(WAIT, policy.sample(2, ELAPSED + 500, WALL + 500, true,
                foreground, Collections.emptyList()));
    }
    @Test public void finalDispatchTimeMustRemainInsideWindow() {
        seedHome(); begin(); assertEquals(WAIT, sample(0)); assertEquals(SEND, sample(100));
        assertTrue(policy.timeValid(ELAPSED + 1999, WALL + 1999));
        assertFalse(policy.timeValid(ELAPSED + 2000, WALL + 2000));
    }
    @Test public void deadlineIsStrictEvenWhenHomeLooksReady() {
        seedHome(); begin(); assertEquals(WAIT, sample(1950));
        assertEquals(CANCEL, sample(2050));
    }
    @Test public void atMostOneAttemptForOneSignal() {
        seedHome(); begin(); assertEquals(WAIT, sample(0)); assertEquals(SEND, sample(100));
        assertEquals(CANCEL, sample(200)); assertEquals(CANCEL, sample(1000));
    }
    @Test public void permissionFailureCancelsAndCannotRetrySameSignal() {
        seedHome(); begin(); assertEquals(WAIT, sample(0));
        assertEquals(CANCEL, policy.sample(1, ELAPSED + 100, WALL + 100, false,
                foreground, Collections.emptyList()));
        assertEquals(CANCEL, sample(200));
    }
    @Test public void unavailableQueryCancels() {
        seedHome(); begin();
        assertEquals(CANCEL, policy.sample(1, ELAPSED, WALL, true, foreground, null));
    }
    @Test public void unknownClassCancels() {
        seedHome(); begin();
        assertEquals(CANCEL, sample(10, new NativeHomePolicy.Event(WALL + 1, 1,
                NativeHomePolicy.STOCK_PACKAGE, null)));
    }
    @Test public void unknownPauseMetadataCancels() {
        seedHome(); begin();
        assertEquals(CANCEL, sample(10, new NativeHomePolicy.Event(WALL + 1, 2, "", "")));
    }
    @Test public void screenOffCancels() {
        seedHome(); begin();
        assertEquals(CANCEL, sample(10, new NativeHomePolicy.Event(WALL + 1, 16, null, null)));
    }
    @Test public void clockJumpCancels() {
        seedHome(); begin();
        assertEquals(CANCEL, policy.sample(1, ELAPSED + 100, WALL + 1000, true,
                foreground, Collections.emptyList()));
    }
    @Test public void futureEventCancels() {
        seedHome(); begin(); assertEquals(CANCEL, sample(10, home(WALL + 100, 1)));
    }
    @Test public void newerHomeSupersedesOldPendingSample() {
        seedHome(); begin(); assertEquals(WAIT, sample(0));
        policy.begin(2, ELAPSED + 50, WALL + 50);
        assertEquals(CANCEL, sample(100));
        List<NativeHomePolicy.Event> none = Collections.emptyList();
        assertEquals(WAIT, policy.sample(2, ELAPSED + 150, WALL + 150, true, foreground, none));
        assertEquals(SEND, policy.sample(2, ELAPSED + 250, WALL + 250, true, foreground, none));
    }
    @Test public void oldOverlappingEventsDoNotUndoLatestForeground() {
        foreground.accept(home(WALL - 20, 1));
        foreground.accept(new NativeHomePolicy.Event(WALL - 10, 1, "settings", "Screen"));
        foreground.accept(home(WALL - 20, 1));
        assertFalse(foreground.stock());
    }
    @Test public void unrelatedStopDoesNotDeactivateCurrentHome() {
        seedHome(); foreground.accept(new NativeHomePolicy.Event(WALL - 10, 23, "app", "Screen"));
        assertTrue(foreground.stock());
    }
    @Test public void olderDelayedResumeCannotOverrideANewerStopObservation() {
        seedHome(); foreground.accept(new NativeHomePolicy.Event(WALL - 10, 23, "app", "Screen"));
        foreground.accept(new NativeHomePolicy.Event(WALL - 20, 1, "app", "Screen"));
        assertTrue(foreground.stock());
    }
    @Test public void uncertainHomeIntervalCannotCrossFreshnessBoundary() {
        begin(); foreground.accept(new NativeHomePolicy.Event(ELAPSED - 251, ELAPSED - 201,
                1, NativeHomePolicy.STOCK_PACKAGE, NativeHomePolicy.STOCK_HOME));
        assertEquals(WAIT, sample(100)); assertEquals(WAIT, sample(200));
    }
    @Test public void wholeHomeIntervalMustBeInThePast() {
        begin(); foreground.accept(new NativeHomePolicy.Event(ELAPSED - 10, ELAPSED + 40,
                1, NativeHomePolicy.STOCK_PACKAGE, NativeHomePolicy.STOCK_HOME));
        assertEquals(WAIT, sample(0)); assertEquals(WAIT, sample(40));
        assertEquals(SEND, sample(140));
    }
    @Test public void possiblePostSignalConflictCancels() {
        seedHome(); begin();
        assertEquals(CANCEL, sample(100, new NativeHomePolicy.Event(ELAPSED - 30, ELAPSED + 20,
                1, "settings", "Screen")));
    }
    @Test public void entirelyPresignalConflictDoesNotCancelFreshHome() {
        begin();
        NativeHomePolicy.Event other = new NativeHomePolicy.Event(ELAPSED - 120,
                ELAPSED - 70, 1, "settings", "Screen");
        assertEquals(WAIT, sample(100, other, home(ELAPSED - 50, 1)));
        assertEquals(SEND, sample(200));
    }
}
