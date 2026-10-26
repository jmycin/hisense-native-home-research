package com.gathin.tvhome;

import java.util.List;

/** Pure fail-open policy. Retains only the latest foreground state, never a usage history. */
final class NativeHomePolicy {
    static final String STOCK_PACKAGE = "com.google.android.tvlauncher";
    static final String STOCK_HOME = STOCK_PACKAGE + ".MainActivity";
    static final long PRESIGNAL_MS = 250;
    static final long WINDOW_MS = 2000;
    static final long STABLE_MS = 100;
    enum Decision { WAIT, CANCEL, SEND }

    static final class Event {
        static final int RESUMED = 1, PAUSED = 2, SCREEN_OFF = 16, KEYGUARD = 17,
                STOPPED = 23, SHUTDOWN = 26;
        final long time, latestTime;
        final int type;
        final String pkg, cls;
        Event(long time, int type, String pkg, String cls) {
            this(time, time, type, pkg, cls);
        }
        Event(long time, long latestTime, int type, String pkg, String cls) {
            this.time = time; this.latestTime = latestTime;
            this.type = type; this.pkg = pkg; this.cls = cls;
        }
        boolean stock() { return STOCK_PACKAGE.equals(pkg) && STOCK_HOME.equals(cls); }
        boolean activity() { return type == RESUMED || type == PAUSED || type == STOPPED; }
        boolean named() { return pkg != null && !pkg.isEmpty() && cls != null && !cls.isEmpty(); }
    }

    static final class Foreground {
        String pkg, cls;
        boolean active;
        long time = -1, latestTime = -1;
        long observedThrough = -1;
        void clear() { pkg = null; cls = null; active = false; time = latestTime = observedThrough = -1; }
        void accept(Event event) {
            if (event.time < observedThrough) return; // Ignore older overlap/late events.
            if (event.activity() || event.type == Event.SCREEN_OFF || event.type == Event.KEYGUARD
                    || event.type == Event.SHUTDOWN) observedThrough = event.time;
            if (event.type == Event.RESUMED) {
                pkg = event.pkg; cls = event.cls;
                active = event.named();
                time = event.time;
                latestTime = event.latestTime;
            } else if (event.activity() && event.pkg != null && event.cls != null
                    && event.pkg.equals(pkg) && event.cls.equals(cls)) {
                active = false;
                time = event.time;
            } else if (event.type == Event.SCREEN_OFF || event.type == Event.KEYGUARD
                    || event.type == Event.SHUTDOWN || (event.activity()
                    && !event.named())) {
                pkg = null; cls = null; active = false; time = event.time;
            }
        }
        boolean stock() {
            return active && STOCK_PACKAGE.equals(pkg) && STOCK_HOME.equals(cls);
        }
    }

    private long generation, startedElapsed, startedWall, firstStable = -1;
    private boolean pending;

    void begin(long token, long elapsed, long wall) {
        generation = token; startedElapsed = elapsed; startedWall = wall;
        firstStable = -1; pending = true;
    }

    boolean windowOpen(long token, long elapsed, long wall) {
        return pending && token == generation && timeValid(elapsed, wall);
    }

    boolean timeValid(long elapsed, long wall) {
        long age = elapsed - startedElapsed;
        return age >= 0 && age < WINDOW_MS
                && Math.abs((wall - startedWall) - age) <= 250;
    }

    Decision sample(long token, long elapsed, long wall, boolean permitted,
            Foreground foreground, List<Event> events) {
        if (token != generation || !pending) return Decision.CANCEL;
        if (!permitted || events == null || !windowOpen(token, elapsed, wall)) return cancel();
        for (Event event : events) {
            if (event.time < 0 || event.time > elapsed || event.latestTime < event.time) return cancel();
            if (event.latestTime >= startedElapsed && (event.type == Event.RESUMED && !event.stock()
                    || event.stock() && (event.type == Event.PAUSED || event.type == Event.STOPPED)
                    || event.activity() && !event.named()
                    || event.type == Event.SCREEN_OFF || event.type == Event.KEYGUARD
                    || event.type == Event.SHUTDOWN)) return cancel();
        }
        // Historical foreground state is not readiness: require a RESUMED event associated with
        // this signal, allowing only a small broadcast-delivery ordering tolerance.
        if (!foreground.stock() || foreground.time < startedElapsed - PRESIGNAL_MS
                || foreground.latestTime > elapsed) {
            firstStable = -1;
            return Decision.WAIT;
        }
        if (firstStable < 0) { firstStable = elapsed; return Decision.WAIT; }
        if (elapsed - firstStable < STABLE_MS) return Decision.WAIT;
        pending = false; // Mark spent BEFORE attempting the asynchronous broadcast.
        return Decision.SEND;
    }

    private Decision cancel() { pending = false; return Decision.CANCEL; }
}
