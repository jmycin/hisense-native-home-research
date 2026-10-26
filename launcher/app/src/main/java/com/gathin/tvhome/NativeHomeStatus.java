package com.gathin.tvhome;

/** UI readiness, not merely the persisted boot opt-in; shared with the control-screen tests. */
final class NativeHomeStatus {
    enum Phase { OFF, STARTING, RUNNING, PAUSED, FAILED }
    private NativeHomeStatus() { }

    static Phase phase(boolean optedIn, boolean starting, boolean accepting,
                       boolean observerRunning, String clockError) {
        if (starting) return Phase.STARTING;
        if (clockError != null && !"none".equals(clockError) && !"pending".equals(clockError)
                && !"stopped".equals(clockError)) return Phase.FAILED;
        if (optedIn && accepting && observerRunning && "none".equals(clockError)) return Phase.RUNNING;
        return optedIn ? Phase.PAUSED : Phase.OFF;
    }

    static boolean canStart(Phase phase) {
        return phase != Phase.STARTING && phase != Phase.RUNNING;
    }
}
