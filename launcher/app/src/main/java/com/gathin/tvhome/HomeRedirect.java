package com.gathin.tvhome;

import android.view.accessibility.AccessibilityEvent;

/** Pure event metadata predicate. Never accesses a view tree, event text, or key event. */
final class HomeRedirect {
    private HomeRedirect() {}

    static boolean configured(String expectedPackage, String expectedClass) {
        return expectedPackage != null && !expectedPackage.isEmpty()
                && expectedClass != null && !expectedClass.isEmpty();
    }

    static boolean matches(int eventType, String expectedPackage, String expectedClass,
                           CharSequence eventPackage, CharSequence eventClass) {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && configured(expectedPackage, expectedClass)
                && eventPackage != null && expectedPackage.contentEquals(eventPackage)
                && eventClass != null && expectedClass.contentEquals(eventClass);
    }

    /** Bounded launch attempts; elapsedRealtime values only. No timer, scheduled retry or logging. */
    static final class Gate {
        static final long COOLDOWN_MS = 750;
        static final long BURST_WINDOW_MS = 10_000;
        static final long QUIET_RESET_MS = 30_000;
        static final int MAX_BURST_ATTEMPTS = 3;
        private long lastEvent = -1;
        private long lastAttempt = -1;
        private long windowStart = -1;
        private int attempts;
        private boolean burstBlocked;
        private boolean failed;

        boolean allow(long now) {
            if (failed || now < 0 || (lastEvent >= 0 && now < lastEvent)) return false;
            if (lastEvent >= 0 && now - lastEvent >= QUIET_RESET_MS) {
                lastAttempt = -1;
                windowStart = -1;
                attempts = 0;
                burstBlocked = false;
            }
            lastEvent = now;
            if (burstBlocked) return false;
            if (lastAttempt >= 0 && now - lastAttempt < COOLDOWN_MS) return false;
            if (windowStart < 0 || now - windowStart >= BURST_WINDOW_MS) {
                windowStart = now;
                attempts = 0;
            }
            if (attempts >= MAX_BURST_ATTEMPTS) {
                burstBlocked = true;
                return false;
            }
            lastAttempt = now;
            attempts++;
            return true;
        }

        void fail() {
            failed = true;
        }
    }
}
