package com.gathin.tvhome;

import android.view.accessibility.AccessibilityEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class HomeRedirectTest {
    private static final String PKG = "com.google.android.tvlauncher";
    private static final String HOME = PKG + ".MainActivity";
    private static final int WINDOW = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

    @Test public void exactStockHomeWindowMatches() {
        assertTrue(HomeRedirect.matches(WINDOW, PKG, HOME, PKG, HOME));
    }

    @Test public void anotherPackageWithSameClassDoesNotMatch() {
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, "com.example.other", HOME));
    }

    @Test public void stockInputsPanelDoesNotMatch() {
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, PKG, PKG + ".inputs.InputsPanelActivity"));
    }

    @Test public void packageAndClassPrefixesDoNotMatch() {
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, PKG + ".other", HOME));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, PKG, HOME + "$Dialog"));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, PKG, ".MainActivity"));
    }

    @Test public void otherEventTypesAndCombinedMaskDoNotMatch() {
        assertFalse(HomeRedirect.matches(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, HOME, PKG, HOME));
        assertFalse(HomeRedirect.matches(AccessibilityEvent.TYPE_VIEW_FOCUSED, PKG, HOME, PKG, HOME));
        assertFalse(HomeRedirect.matches(WINDOW | AccessibilityEvent.TYPE_VIEW_FOCUSED, PKG, HOME, PKG, HOME));
        assertFalse(HomeRedirect.matches(0, PKG, HOME, PKG, HOME));
    }

    @Test public void missingEventMetadataDoesNotMatch() {
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, null, HOME));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, PKG, null));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, "", HOME));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, PKG, ""));
    }

    @Test public void absentConfigurationDisablesRedirect() {
        assertFalse(HomeRedirect.matches(WINDOW, "", HOME, PKG, HOME));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, "", PKG, HOME));
        assertFalse(HomeRedirect.matches(WINDOW, null, HOME, PKG, HOME));
        assertFalse(HomeRedirect.matches(WINDOW, PKG, null, PKG, HOME));
    }

    @Test public void charSequenceMetadataMatchesExactly() {
        assertTrue(HomeRedirect.matches(WINDOW, PKG, HOME, new StringBuilder(PKG), new StringBuilder(HOME)));
    }

    @Test public void ownLauncherWindowDoesNotRedirectAgain() {
        assertFalse(HomeRedirect.matches(WINDOW, PKG, HOME, "com.gathin.tvhome", "com.gathin.tvhome.MainActivity"));
    }

    @Test public void duplicateWindowEventsAreDebounced() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        assertTrue(gate.allow(0));
        assertFalse(gate.allow(100));
        assertFalse(gate.allow(749));
        assertTrue(gate.allow(750));
    }

    @Test public void rapidReturnsWithinOneServiceInstanceAreBounded() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        assertTrue(gate.allow(0));
        assertTrue(gate.allow(1000));
        assertTrue(gate.allow(2000));
        assertFalse(gate.allow(3000));
        assertFalse(gate.allow(20_000));
    }

    @Test public void blockedLoopNeedsQuietBeforeAnotherAttempt() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        gate.allow(0); gate.allow(1000); gate.allow(2000);
        assertFalse(gate.allow(3000));
        assertFalse(gate.allow(10_000));
        assertTrue(gate.allow(40_000));
    }

    @Test public void ordinarySeparatedHomePressesDoNotAccumulateForever() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        assertTrue(gate.allow(0));
        assertTrue(gate.allow(4000));
        assertTrue(gate.allow(8000));
        assertTrue(gate.allow(12_000));
        assertTrue(gate.allow(16_000));
    }

    @Test public void duplicateEventsDoNotConsumeLaunchBudget() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        assertTrue(gate.allow(0));
        assertFalse(gate.allow(100));
        assertFalse(gate.allow(200));
        assertTrue(gate.allow(1000));
        assertTrue(gate.allow(2000));
        assertFalse(gate.allow(3000));
    }

    @Test public void launchFailureStaysLatchedUntilServiceReconnect() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        assertTrue(gate.allow(0));
        gate.fail();
        assertFalse(gate.allow(60_000));
        assertFalse(gate.allow(120_000));
        assertTrue(new HomeRedirect.Gate().allow(120_000));
    }

    @Test public void invalidOrBackwardClockDoesNotLaunch() {
        HomeRedirect.Gate gate = new HomeRedirect.Gate();
        assertFalse(gate.allow(-1));
        assertTrue(gate.allow(1000));
        assertFalse(gate.allow(999));
        assertTrue(gate.allow(2000));
    }
}
