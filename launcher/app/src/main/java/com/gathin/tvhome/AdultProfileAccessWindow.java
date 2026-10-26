package com.gathin.tvhome;

/** Short-lived, in-memory authorization for returning to the Adult family profile. */
final class AdultProfileAccessWindow {
    static final long VALIDITY_MS = 15L * 60L * 1000L;

    private long authenticatedAtElapsedMs = -1L;

    void markAuthenticated(long elapsedRealtimeMs) {
        authenticatedAtElapsedMs = elapsedRealtimeMs >= 0L ? elapsedRealtimeMs : -1L;
    }

    boolean permitsReentry(long elapsedRealtimeMs) {
        return authenticatedAtElapsedMs >= 0L
                && elapsedRealtimeMs >= authenticatedAtElapsedMs
                && elapsedRealtimeMs - authenticatedAtElapsedMs < VALIDITY_MS;
    }

    void invalidate() {
        authenticatedAtElapsedMs = -1L;
    }
}
