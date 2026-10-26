package com.gathin.tvhome;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class NativeHomeClockTest {
    private static final long E = 10000000, W = 1000000000000L;
    private final NativeHomeClock clock = new NativeHomeClock();
    private NativeHomeClock.Mark start(long time) { return new NativeHomeClock.Mark(time, 19); }
    private NativeHomeClock.Mark stop(long time) { return new NativeHomeClock.Mark(time, 20); }
    private NativeHomeClock.Mark sentinel() { return start(W - 1824); }
    private List<NativeHomeClock.Mark> complete() {
        return Arrays.asList(sentinel(), stop(W + 4499 - 1824), start(W + 4510 - 1824));
    }
    private void observed() { assertTrue(clock.observe(Collections.singletonList(sentinel()), E, W)); }
    private void bracketed() {
        observed(); assertTrue(clock.canRefresh(Collections.singletonList(sentinel()), E + 4500, W + 4500));
        assertTrue(clock.refreshBracket(E + 4500, E + 4520, W + 4520));
    }
    private void ready() {
        bracketed(); assertTrue(clock.calibrate(complete(), E + 4600, W + 4600));
    }
    @Test public void noSentinelDoesNotInventCalibration() {
        assertFalse(clock.observe(Collections.emptyList(), E, W)); assertFalse(clock.ready());
    }
    @Test public void startupBudgetNeverAcceptsLateOrBackwardActivation() {
        assertTrue(NativeHomeClock.withinStartup(E, E + 8999));
        assertFalse(NativeHomeClock.withinStartup(E, E + 9000));
        assertFalse(NativeHomeClock.withinStartup(E, E - 1));
    }
    @Test public void separationStartsAtObservationNotOldEventTime() {
        assertTrue(clock.observe(Collections.singletonList(start(W - 9000)), E, W));
        assertFalse(clock.canRefresh(Collections.singletonList(start(W - 9000)), E + 4499, W + 4499));
    }
    @Test public void separationIsInclusiveAt4500Milliseconds() {
        observed(); assertTrue(clock.canRefresh(Collections.singletonList(sentinel()), E + 4500, W + 4500));
    }
    @Test public void sentinelClockRebaseBeforeRefreshFails() {
        observed(); assertFalse(clock.canRefresh(Collections.singletonList(start(W - 1823)), E + 4500, W + 4500));
        assertEquals("rebase", clock.error());
    }
    @Test public void startupClockJumpFails() {
        observed(); assertFalse(clock.canRefresh(Collections.singletonList(sentinel()), E + 4500, W + 4551));
        assertEquals("clock_jump", clock.error());
    }
    @Test public void bracketAbove100MillisecondsFails() {
        observed(); assertFalse(clock.refreshBracket(E + 4500, E + 4601, W + 4601));
        assertEquals("wide_bracket", clock.error());
    }
    @Test public void bracketAt100MillisecondsIsAllowed() {
        observed(); assertTrue(clock.refreshBracket(E + 4500, E + 4600, W + 4600));
    }
    @Test public void earlyRefreshCannotProduceAnAnchor() {
        observed(); assertFalse(clock.refreshBracket(E + 4499, E + 4500, W + 4500));
    }
    @Test public void delayedOldStartAboveBaselineButBelowCeilingCannotCalibrate() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(sentinel(),
                stop(W + 100), start(W + 300)), E + 4600, W + 4600));
        assertEquals("pending", clock.error());
    }
    @Test public void exactOldEventCeilingIsExcluded() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(sentinel(),
                stop(clock.oldEventCeiling()), start(clock.oldEventCeiling())), E + 4600, W + 4600));
    }
    @Test public void bothNewStopAndStartAreRequired() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(sentinel(),
                start(W + 4510 - 1824)), E + 4600, W + 4600));
    }
    @Test public void moreThanOneNewStartIsAmbiguous() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(sentinel(),
                stop(W + 4499 - 1824), start(W + 4510 - 1824), start(W + 4511 - 1824)), E + 4600, W + 4600));
        assertEquals("ambiguous_anchor", clock.error());
    }
    @Test public void stopAfterStartIsRejected() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(sentinel(),
                stop(W + 4511 - 1824), start(W + 4510 - 1824)), E + 4600, W + 4600));
    }
    @Test public void sameMillisecondStopStartHasNoProvenOrdering() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(sentinel(),
                stop(W + 4510 - 1824), start(W + 4510 - 1824)), E + 4600, W + 4600));
        assertEquals("ambiguous_anchor", clock.error());
    }
    @Test public void rebaseBetweenCallAndPublicationIsRejected() {
        bracketed(); assertFalse(clock.calibrate(Arrays.asList(start(W - 1823),
                stop(W + 4499 - 1823), start(W + 4510 - 1823)), E + 4600, W + 4600));
        assertEquals("rebase", clock.error());
    }
    @Test public void publicationBudgetIsStrict() {
        bracketed(); assertFalse(clock.calibrate(complete(), E + 6520, W + 6520));
        assertEquals("publication_timeout", clock.error());
    }
    @Test public void known1824MillisecondOffsetMapsToElapsedInterval() {
        ready(); NativeHomeClock.Bounds b = clock.elapsedBounds(W + 10000 - 1824);
        assertEquals(E + 9990, b.first); assertEquals(E + 10010, b.last);
    }
    @Test public void calibratedStateStillRequiresUnchangedOwnEvents() {
        ready(); assertTrue(clock.confirm(complete()));
        assertFalse(clock.confirm(Arrays.asList(sentinel(), stop(W + 4499 - 1824), start(W + 4511 - 1824))));
        assertFalse(clock.ready()); assertEquals("rebase", clock.error());
    }
    @Test public void missingOrDuplicateAnchorFailsClosed() {
        ready(); assertFalse(clock.confirm(Arrays.asList(sentinel(), stop(W + 4499 - 1824))));
    }
    @Test public void ownRetrievalIncludesOneMillisecondLowerGuard() {
        ready(); assertEquals(sentinel().time - 1, clock.ownBegin(W + 10000));
        assertEquals(W + 4510 - 1824 + 1, clock.ownEnd());
    }
    @Test public void mappedRangeKeepsExclusiveEndAndLowerGuard() {
        ready(); long signal = E + 10000;
        assertEquals(signal - 250 + clock.anchorTime() - (E + 4520) - 1,
                clock.queryBegin(signal, W + 10000));
        assertEquals(signal + clock.anchorTime() - (E + 4500) + 1, clock.queryEnd(signal));
    }
    @Test public void futureMappedStartIsClippedOnlyForRetrieval() {
        ready(); assertEquals(100L, clock.queryBegin(E + 10000, 100L));
        NativeHomeClock.Bounds b = clock.elapsedBounds(clock.anchorTime());
        assertEquals(E + 4500, b.first); assertEquals(E + 4520, b.last);
    }
}
