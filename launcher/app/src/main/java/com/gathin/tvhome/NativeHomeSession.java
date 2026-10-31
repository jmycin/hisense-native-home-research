package com.gathin.tvhome;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Process-shared serialization of Start tickets, Home generations, Stop, and final dispatch. */
final class NativeHomeSession {
    private long epoch;
    private long ownerTicket = -1;
    private boolean accepting, startPending, startDelivered, stopDurable;
    private String stopError = "uncertain";
    private boolean processRecoveryUnused = true, startSubmitted;

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
        processRecoveryUnused = false;
        accepting = false;
        startPending = true;
        startDelivered = false;
        startSubmitted = false;
        stopDurable = false;
        stopError = "uncertain";
        ownerTicket = ++epoch; // Own pending calibration too, so its destruction releases the ticket.
        return ownerTicket;
    }

    /** One OS recreation admission per process, serialized with durable Stop and user Start. */
    synchronized long requestProcessRecovery(BooleanSupplier optedIn,
            BooleanSupplier prerequisites, BooleanSupplier consumeBudget) {
        if (!processRecoveryUnused) return -1;
        processRecoveryUnused = false;
        long before = epoch;
        try {
            if (!optedIn.getAsBoolean() || epoch != before) return -1;
            if (!prerequisites.getAsBoolean() || epoch != before) return -1;
            if (!consumeBudget.getAsBoolean() || epoch != before) return -1;
        } catch (RuntimeException unavailable) { return -1; }
        // Preference callbacks may be reentrant even while this Java monitor is held.
        if (epoch != before || startPending || accepting) return -1;
        return requestStart();
    }

    synchronized void startSubmitted(long ticket) {
        if (startCurrent(ticket)) startSubmitted = true;
    }

    synchronized boolean hasServiceWork(long serviceTicket) {
        return accepting || startCurrent(serviceTicket) || (startPending && startSubmitted);
    }

    synchronized boolean retainService(long serviceTicket, boolean optedIn) {
        return optedIn && hasServiceWork(serviceTicket);
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

    /** An admitted OS recreation may inherit our own foreground ServiceRecord. */
    synchronized boolean duringStart(long ticket, boolean processRecovery, BooleanSupplier optedIn,
            Runnable normalizeOwnForeground, Runnable startOwnForeground) {
        if (!startCurrent(ticket) || !optedIn.getAsBoolean() || !startCurrent(ticket)) return false;
        if (processRecovery) normalizeOwnForeground.run();
        // Reentrant callbacks must not resurrect Stop or replace a newer accepted start.
        if (!startCurrent(ticket) || !optedIn.getAsBoolean() || !startCurrent(ticket)) return false;
        startOwnForeground.run();
        return startCurrent(ticket) && optedIn.getAsBoolean() && startCurrent(ticket);
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
        processRecoveryUnused = false;
        accepting = false; startPending = false; startDelivered = false; startSubmitted = false;
        ownerTicket = -1; ++epoch;
    }
    synchronized void serviceDestroyed(long ticket) { if (ownerTicket == ticket) invalidate(); }
    synchronized boolean stopDurable() { return stopDurable; }
    synchronized String stopError() { return stopError; }
}
