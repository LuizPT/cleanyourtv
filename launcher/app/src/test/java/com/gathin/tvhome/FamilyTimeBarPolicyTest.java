package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class FamilyTimeBarPolicyTest {
    private static final long MINUTE = FamilyTimePolicy.MINUTE_MS;

    @Test public void barRequiresAdultConsentActiveChildSessionAndVisibleScreen() {
        assertTrue(FamilyTimeBarPolicy.shouldDisplay(true, true, true, true,
                FamilyProfileStore.Profile.CHILD_TV));
        assertTrue(FamilyTimeBarPolicy.shouldDisplay(true, true, true, true,
                FamilyProfileStore.Profile.XBOX));
        assertFalse(FamilyTimeBarPolicy.shouldDisplay(true, false, true, true,
                FamilyProfileStore.Profile.CHILD_TV));
        assertFalse(FamilyTimeBarPolicy.shouldDisplay(false, true, true, true,
                FamilyProfileStore.Profile.CHILD_TV));
        assertFalse(FamilyTimeBarPolicy.shouldDisplay(true, true, false, true,
                FamilyProfileStore.Profile.CHILD_TV));
        assertFalse(FamilyTimeBarPolicy.shouldDisplay(true, true, true, false,
                FamilyProfileStore.Profile.CHILD_TV));
        assertFalse(FamilyTimeBarPolicy.shouldDisplay(true, true, true, true,
                FamilyProfileStore.Profile.ADULT));
    }

    @Test public void displayedMinutesRoundUpWithoutShowingExtraTime() {
        assertEquals(0, FamilyTimeBarPolicy.displayMinutes(0L));
        assertEquals(1, FamilyTimeBarPolicy.displayMinutes(1L));
        assertEquals(1, FamilyTimeBarPolicy.displayMinutes(MINUTE));
        assertEquals(2, FamilyTimeBarPolicy.displayMinutes(MINUTE + 1L));
    }

    @Test public void skippedWarningThresholdsReportTheCurrentRemainingTime() {
        assertEquals(5, FamilyTimeBarPolicy.warningMinutes(Collections.singletonList(5), 4L * MINUTE + 1L));
        assertEquals(3, FamilyTimeBarPolicy.warningMinutes(Arrays.asList(10, 5), 2L * MINUTE + 1L));
        assertEquals(0, FamilyTimeBarPolicy.warningMinutes(Collections.emptyList(), MINUTE));
    }

    @Test public void progressIsBoundedAndUsesProgressiveWarningColours() {
        assertEquals(0, FamilyTimeBarPolicy.progressPercent(-1L, 60L * MINUTE));
        assertEquals(50, FamilyTimeBarPolicy.progressPercent(30L * MINUTE, 60L * MINUTE));
        assertEquals(100, FamilyTimeBarPolicy.progressPercent(61L * MINUTE, 60L * MINUTE));
        assertEquals(FamilyTimeBarPolicy.Colour.GREEN, FamilyTimeBarPolicy.colourForRemaining(11L * MINUTE));
        assertEquals(FamilyTimeBarPolicy.Colour.YELLOW, FamilyTimeBarPolicy.colourForRemaining(10L * MINUTE));
        assertEquals(FamilyTimeBarPolicy.Colour.ORANGE, FamilyTimeBarPolicy.colourForRemaining(5L * MINUTE));
        assertEquals(FamilyTimeBarPolicy.Colour.RED, FamilyTimeBarPolicy.colourForRemaining(MINUTE));
        assertEquals(FamilyTimeBarPolicy.Colour.RED, FamilyTimeBarPolicy.colourForRemaining(0L));
    }
}
