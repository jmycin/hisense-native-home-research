package com.gathin.tvhome;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Process-shared serialization of Start tickets, Home generations, Stop, and final dispatch. */
final class NativeHomeSession {
    private long epoch;
    private long ownerTicket = -1;
    private boolean accepting, startPending, startDelivered, stopDurable;
    private String stopError = "uncertain";

    static final class StartRequest {
        final long ticket;
        final String problem;
        StartRequest(long ticket, String problem) { this.ticket = ticket; this.problem = problem; }
        boolean queued() { return ticket >= 0; }
    }

    /** Actual user-Start admission: duplicates do not disturb calibration or a pending Home. */
    synchronized StartRequest requestStartIfIdle(Supplier<String> readiness) {
        String problem = readiness.get(); // A refused request owns no ticket and changes no state.
        if (problem != null) return new StartRequest(-1, problem);
        if (startPending || accepting) return new StartRequest(-1, null);
        return new StartRequest(requestStart(), null);
    }

    synchronized long requestStart() {
        accepting = false;
        startPending = true;
        startDelivered = false;
        stopDurable = false;
        stopError = "uncertain";
        ownerTicket = ++epoch; // Own pending calibration too, so its destruction releases the ticket.
        return ownerTicket;
    }

    synchronized boolean startCurrent(long ticket) { return startPending && epoch == ticket; }
    synchronized boolean starting() { return startPending; }
    synchronized boolean activeOrStarting() { return startPending || accepting; }

    /** Exactly one onStartCommand delivery may initialize the observer for this reservation. */
    synchronized boolean claimStart(long ticket) {
        if (!startCurrent(ticket) || startDelivered) return false;
        startDelivered = true;
        return true;
    }

    synchronized boolean activate(long ticket) {
        if (!startCurrent(ticket)) return false;
        startPending = false;
        accepting = true;
        ownerTicket = ticket;
        return true;
    }

    synchronized long homeSignal() { return accepting ? ++epoch : -1; }
    synchronized boolean current(long token) { return accepting && epoch == token; }
    synchronized boolean accepting() { return accepting; }

    synchronized boolean duringStart(long ticket, Runnable operation) {
        if (!startCurrent(ticket)) return false;
        operation.run(); // Stop shares this barrier, including calibration foreground refreshes.
        return true;
    }

    synchronized boolean duringCurrent(long token, Runnable operation) {
        if (!current(token)) return false;
        operation.run(); // Failure handling must not cancel a newer Home or Start generation.
        return true;
    }

    synchronized boolean dispatch(long token, BooleanSupplier ready, Runnable send) {
        if (!current(token) || !ready.getAsBoolean()) return false;
        // Stop uses this same monitor. A broadcast already sent before Stop acquired it cannot
        // be recalled; after Stop invalidates the epoch, no pending generation can dispatch.
        send.run();
        return true;
    }

    synchronized boolean stop(BooleanSupplier saveOff) {
        invalidate(); // Cancel synchronously BEFORE the potentially failing persistence operation.
        boolean saved;
        try { saved = saveOff.getAsBoolean(); }
        catch (RuntimeException failure) { saved = false; }
        stopDurable = saved;
        stopError = saved ? "none" : "commit_failed";
        return saved;
    }

    synchronized void invalidate() {
        accepting = false; startPending = false; startDelivered = false; ownerTicket = -1; ++epoch;
    }
    synchronized void serviceDestroyed(long ticket) { if (ownerTicket == ticket) invalidate(); }
    synchronized boolean stopDurable() { return stopDurable; }
    synchronized String stopError() { return stopError; }
}
