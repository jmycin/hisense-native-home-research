package com.gathin.tvhome;

import java.util.List;

/** Calibrates UsageEvents' clock using only this service's own foreground transitions. */
final class NativeHomeClock {
    static final long DRIFT_BOUND = 2000, SEPARATION_MS = 4500, STABLE_CLOCK_MS = 50;
    static final long MAX_BRACKET_MS = 100, PUBLICATION_MS = 2000, OWN_LOOKBACK_MS = 10000;
    static final long STARTUP_MS = 9000;
    static final int START = 19, STOP = 20;
    static final class Mark {
        final long time; final int type;
        Mark(long time, int type) { this.time = time; this.type = type; }
    }
    static final class Bounds {
        final long first, last;
        Bounds(long first, long last) { this.first = first; this.last = last; }
    }
    private Mark sentinel, anchor, stopped;
    private long observedElapsed, observedWall, first, last, refreshWall;
    private boolean refreshed, ready;
    private String error = "pending";

    String error() { return error; }
    boolean ready() { return ready; }
    long observationElapsed() { return observedElapsed; }
    long observationWall() { return observedWall; }
    long anchorTime() { return anchor == null ? -1 : anchor.time; }
    long sentinelTime() { return sentinel == null ? -1 : sentinel.time; }
    static boolean withinStartup(long began, long now) { return now >= began && now - began < STARTUP_MS; }
    long oldEventCeiling() { return observedWall + DRIFT_BOUND + STABLE_CLOCK_MS; }

    boolean observe(List<Mark> marks, long elapsed, long wall) {
        if (sentinel != null || marks == null) return fail("ambiguous_anchor");
        Mark newest = null;
        for (Mark mark : marks) {
            if (mark.type == START && mark.time >= wall - OWN_LOOKBACK_MS
                    && mark.time <= wall + DRIFT_BOUND
                    && (newest == null || mark.time > newest.time)) newest = mark;
        }
        if (newest == null) return false;
        if (count(marks, newest) != 1) return fail("ambiguous_anchor");
        sentinel = newest; observedElapsed = elapsed; observedWall = wall;
        return true;
    }

    boolean stable(long elapsed, long wall) {
        return elapsed >= observedElapsed
                && Math.abs((wall - observedWall) - (elapsed - observedElapsed)) <= STABLE_CLOCK_MS;
    }

    boolean canRefresh(List<Mark> marks, long elapsed, long wall) {
        if (sentinel == null || count(marks, sentinel) != 1) return fail("rebase");
        if (!stable(elapsed, wall)) return fail("clock_jump");
        return elapsed - observedElapsed >= SEPARATION_MS;
    }

    boolean refreshBracket(long before, long after, long wallAfter) {
        if (refreshed || sentinel == null || before - observedElapsed < SEPARATION_MS)
            return fail("ambiguous_anchor");
        if (after < before || after - before > MAX_BRACKET_MS) return fail("wide_bracket");
        if (!stable(after, wallAfter)) return fail("clock_jump");
        // A previous START cannot exceed this ceiling under the pinned 2s correction bound.
        if (wallAfter - (after - before) - DRIFT_BOUND <= oldEventCeiling())
            return fail("ambiguous_anchor");
        first = before; last = after; refreshWall = wallAfter; refreshed = true;
        return true;
    }

    boolean calibrate(List<Mark> marks, long elapsed, long wall) {
        if (!refreshed || !stable(elapsed, wall)) return fail("clock_jump");
        if (elapsed - last >= PUBLICATION_MS) return fail("publication_timeout");
        if (count(marks, sentinel) != 1) return fail("rebase");
        Mark nextStart = null, nextStop = null;
        for (Mark mark : marks) {
            if (mark.time <= oldEventCeiling()) continue;
            if (mark.type == START) {
                if (nextStart != null) return fail("ambiguous_anchor");
                nextStart = mark;
            } else if (mark.type == STOP) {
                if (nextStop != null) return fail("ambiguous_anchor");
                nextStop = mark;
            }
        }
        if (nextStart == null || nextStop == null) return false;
        if (nextStop.time >= nextStart.time
                || nextStart.time < refreshWall - (last - first) - DRIFT_BOUND - STABLE_CLOCK_MS
                || nextStart.time > refreshWall + DRIFT_BOUND + STABLE_CLOCK_MS)
            return fail("ambiguous_anchor");
        anchor = nextStart; stopped = nextStop; ready = true; error = "none";
        return true;
    }

    boolean confirm(List<Mark> marks) {
        if (!ready || count(marks, sentinel) != 1 || count(marks, stopped) != 1
                || count(marks, anchor) != 1) return fail("rebase");
        return true;
    }

    Bounds elapsedBounds(long usageTimestamp) {
        if (!ready) throw new IllegalStateException("Clock is not calibrated");
        return new Bounds(usageTimestamp - anchor.time + first, usageTimestamp - anchor.time + last);
    }

    long queryBegin(long signalElapsed, long actualWall) {
        return Math.max(0, Math.min(actualWall,
                signalElapsed - NativeHomePolicy.PRESIGNAL_MS + anchor.time - last - 1));
    }
    long queryEnd(long nowElapsed) { return nowElapsed + anchor.time - first + 1; }
    long ownBegin(long actualWall) { return Math.max(0, Math.min(sentinel.time - 1, actualWall)); }
    long ownEnd() { return anchor.time + 1; }

    private static int count(List<Mark> marks, Mark wanted) {
        if (marks == null || wanted == null) return 0;
        int count = 0;
        for (Mark mark : marks) if (mark.time == wanted.time && mark.type == wanted.type) count++;
        return count;
    }
    private boolean fail(String reason) { ready = false; error = reason; return false; }
}
