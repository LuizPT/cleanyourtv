package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class FamilyTimePolicyTest {
    private static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, LISBON);
    }

    @Test public void childIsBlockedBeforeSevenAndAtTwentyTwo() {
        assertFalse(FamilyTimePolicy.evaluate(FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 6, 59), 0).allowed);
        assertTrue(FamilyTimePolicy.evaluate(FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 7, 0), 0).allowed);
        assertTrue(FamilyTimePolicy.evaluate(FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 21, 59), 0).allowed);
        assertFalse(FamilyTimePolicy.evaluate(FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 22, 0), 0).allowed);
    }

    @Test public void childHasSixtyMinutesEveryDay() {
        assertEquals(60L * 60_000L, FamilyTimePolicy.dailyBudgetMs(FamilyTimePolicy.Profile.CHILD_TV, DayOfWeek.SATURDAY));
        FamilyTimePolicy.Decision nearLimit = FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 12, 0), 59L * 60_000L);
        assertTrue(nearLimit.allowed);
        assertEquals(60_000L, nearLimit.remainingMs);
        FamilyTimePolicy.Decision exhausted = FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 12, 0), 60L * 60_000L);
        assertFalse(exhausted.allowed);
        assertEquals(FamilyTimePolicy.Reason.DAILY_LIMIT_REACHED, exhausted.reason);
    }

    @Test public void xboxGetsExtraHourOnlyOnWeekend() {
        assertEquals(60L * 60_000L, FamilyTimePolicy.dailyBudgetMs(FamilyTimePolicy.Profile.XBOX, DayOfWeek.FRIDAY));
        assertEquals(120L * 60_000L, FamilyTimePolicy.dailyBudgetMs(FamilyTimePolicy.Profile.XBOX, DayOfWeek.SATURDAY));
        assertEquals(120L * 60_000L, FamilyTimePolicy.dailyBudgetMs(FamilyTimePolicy.Profile.XBOX, DayOfWeek.SUNDAY));
        assertEquals(60L * 60_000L, FamilyTimePolicy.dailyBudgetMs(FamilyTimePolicy.Profile.XBOX, DayOfWeek.MONDAY));
    }

    @Test public void xboxWithNinetyMinutesUsedOnlyAllowedOnWeekend() {
        assertTrue(FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.XBOX, at(2026, 10, 24, 12, 0), 90L * 60_000L).allowed);
        assertFalse(FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.XBOX, at(2026, 10, 26, 12, 0), 90L * 60_000L).allowed);
    }

    @Test public void adultIsAlwaysAllowedByTimePolicy() {
        assertTrue(FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.ADULT, at(2026, 10, 23, 23, 0), Long.MAX_VALUE).allowed);
        assertEquals(Long.MAX_VALUE, FamilyTimePolicy.dailyBudgetMs(
                FamilyTimePolicy.Profile.ADULT, DayOfWeek.MONDAY));
    }

    @Test public void timePolicyObservesLocalTimeOnDstChange() {
        assertFalse(FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 25, 6, 59), 0).allowed);
        assertTrue(FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 25, 7, 0), 0).allowed);
    }

    @Test public void consumedBeyondBudgetCannotIncreaseRemaining() {
        FamilyTimePolicy.Decision decision = FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.XBOX, at(2026, 10, 24, 13, 0), Long.MAX_VALUE);
        assertFalse(decision.allowed);
        assertEquals(0L, decision.remainingMs);
    }

    @Test public void adultClockUncertaintyDoesNotLockOutAdult() {
        FamilyTimePolicy.Decision decision = FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.ADULT, at(2026, 10, 23, 23, 0), 0, 0, true);
        assertTrue(decision.allowed);
        assertEquals(FamilyTimePolicy.Reason.ALLOWED, decision.reason);
    }

    @Test public void extensionsAddTimeAndUncertainClockBlocksChildProfiles() {
        FamilyTimePolicy.Decision extended = FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.CHILD_TV, at(2026, 10, 23, 12, 0), 60L * 60_000L,
                15L * 60_000L, false);
        assertTrue(extended.allowed);
        assertEquals(15L * 60_000L, extended.remainingMs);

        FamilyTimePolicy.Decision uncertain = FamilyTimePolicy.evaluate(
                FamilyTimePolicy.Profile.XBOX, at(2026, 10, 24, 12, 0), 0, 0, true);
        assertFalse(uncertain.allowed);
        assertEquals(FamilyTimePolicy.Reason.CLOCK_UNCERTAIN, uncertain.reason);
    }
}
