package com.gathin.tvhome;

/** Fixed private startup counters, not a watchdog or a record of app use. */
final class NativeHomeRecoveryBudget {
    static final int MAX_ATTEMPTS = 2;
    static final long WINDOW_MS = 10 * 60 * 1000;

    static final class State {
        final int boot, attempts;
        final long beganElapsed;
        State(int boot, int attempts, long beganElapsed) {
            this.boot = boot; this.attempts = attempts; this.beganElapsed = beganElapsed;
        }
    }

    interface Store {
        State read(); // null means all three budget keys are absent, not an unreadable record.
        boolean write(State state);
    }

    private NativeHomeRecoveryBudget() { }

    /** Caller holds the same session monitor used by Stop through this commit and admission. */
    static boolean claim(Store store, int boot, long elapsed) {
        if (store == null || boot < 0 || elapsed < 0) return false;
        State prior = store.read();
        int attempts = 0;
        long began = elapsed;
        if (prior != null) {
            if (prior.boot < 0 || prior.boot > boot || prior.attempts < 0
                    || prior.attempts > MAX_ATTEMPTS || prior.beganElapsed < 0) return false;
            if (prior.boot == boot) {
                if (elapsed < prior.beganElapsed) return false;
                if (elapsed - prior.beganElapsed < WINDOW_MS) {
                    attempts = prior.attempts; began = prior.beganElapsed;
                }
            }
        }
        if (attempts >= MAX_ATTEMPTS) return false;
        // Consume before reserving/starting: a crash after this commit can lose a slot, not add one.
        return store.write(new State(boot, attempts + 1, began));
    }

    static boolean resetForUserStart(Store store, int boot, long elapsed) {
        return store != null && boot >= 0 && elapsed >= 0
                && store.write(new State(boot, 0, elapsed));
    }
}
