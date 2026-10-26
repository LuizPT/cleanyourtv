package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdultProfileAccessWindowTest {
    @Test public void successfulAuthenticationAllowsReentryBeforeFifteenMinutes() {
        AdultProfileAccessWindow window = new AdultProfileAccessWindow();
        window.markAuthenticated(1_000L);

        assertTrue(window.permitsReentry(1_000L + AdultProfileAccessWindow.VALIDITY_MS - 1L));
    }

    @Test public void authorizationExpiresAtFifteenMinutes() {
        AdultProfileAccessWindow window = new AdultProfileAccessWindow();
        window.markAuthenticated(1_000L);

        assertFalse(window.permitsReentry(1_000L + AdultProfileAccessWindow.VALIDITY_MS));
        assertFalse(window.permitsReentry(1_000L + AdultProfileAccessWindow.VALIDITY_MS + 1L));
    }

    @Test public void checkingOrNavigatingDoesNotExtendTheWindow() {
        AdultProfileAccessWindow window = new AdultProfileAccessWindow();
        window.markAuthenticated(10_000L);

        assertTrue(window.permitsReentry(10_000L + 5L * 60L * 1000L));
        assertFalse(window.permitsReentry(10_000L + AdultProfileAccessWindow.VALIDITY_MS));
    }

    @Test public void manualLockOrScreenOffInvalidatesImmediately() {
        AdultProfileAccessWindow window = new AdultProfileAccessWindow();
        window.markAuthenticated(10_000L);
        window.invalidate();

        assertFalse(window.permitsReentry(10_001L));
    }

    @Test public void aRecreatedActivityStartsWithNoAuthorization() {
        AdultProfileAccessWindow previousActivity = new AdultProfileAccessWindow();
        previousActivity.markAuthenticated(10_000L);

        AdultProfileAccessWindow recreatedActivity = new AdultProfileAccessWindow();

        assertFalse(recreatedActivity.permitsReentry(10_001L));
    }

    @Test public void invalidOrReversedMonotonicTimesFailClosed() {
        AdultProfileAccessWindow window = new AdultProfileAccessWindow();
        window.markAuthenticated(-1L);
        assertFalse(window.permitsReentry(0L));

        window.markAuthenticated(100L);
        assertFalse(window.permitsReentry(99L));
    }
}
