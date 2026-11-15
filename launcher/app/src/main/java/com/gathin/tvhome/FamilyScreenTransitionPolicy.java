package com.gathin.tvhome;

/** Filters stale, duplicate, or out-of-order screen broadcasts against the device's real state. */
final class FamilyScreenTransitionPolicy {
    enum Transition { IGNORE, SCREEN_OFF, SCREEN_ON }
    static final long BROADCAST_STATE_SETTLE_MS = 250L;

    private boolean screenOffObserved;
    private boolean wakeHandled;

    Transition accept(boolean screenOnAction, boolean deviceInteractive, boolean persistedSessionOff) {
        if (!screenOnAction) {
            if (deviceInteractive || screenOffObserved) return Transition.IGNORE;
            screenOffObserved = true;
            wakeHandled = false;
            return Transition.SCREEN_OFF;
        }
        if (!deviceInteractive || wakeHandled || (!screenOffObserved && !persistedSessionOff)) {
            return Transition.IGNORE;
        }
        screenOffObserved = false;
        wakeHandled = true;
        return Transition.SCREEN_ON;
    }

    void reset() {
        screenOffObserved = false;
        wakeHandled = false;
    }
}
