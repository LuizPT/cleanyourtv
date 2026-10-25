package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

public class FamilyAccessPolicyTest {
    private static final String XBOX_INPUT = "vendor/.TvInput/HW1";

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 23, hour, minute, 0, 0, ZoneId.of("Europe/Lisbon"));
    }

    private static FamilyAccessPolicy.Decision decide(FamilyTimePolicy.Profile profile,
            FamilyAccessPolicy.Target target, String input, int hour, int minute) {
        return FamilyAccessPolicy.evaluate(profile, target, input, XBOX_INPUT, at(hour, minute), 0, 0, false);
    }

    @Test public void childCanOpenAppsAndTunerButNotSettingsOrHdmi() {
        assertTrue(decide(FamilyTimePolicy.Profile.CHILD_TV, FamilyAccessPolicy.Target.APP, null, 12, 0).allowed);
        assertTrue(decide(FamilyTimePolicy.Profile.CHILD_TV, FamilyAccessPolicy.Target.TUNER_INPUT, null, 12, 0).allowed);
        assertFalse(decide(FamilyTimePolicy.Profile.CHILD_TV, FamilyAccessPolicy.Target.SYSTEM_SETTINGS, null, 12, 0).allowed);
        assertFalse(decide(FamilyTimePolicy.Profile.CHILD_TV, FamilyAccessPolicy.Target.PASSTHROUGH_INPUT,
                XBOX_INPUT, 12, 0).allowed);
    }

    @Test public void xboxAllowsOnlyConfiguredHdmiWithinTimeLimit() {
        assertTrue(decide(FamilyTimePolicy.Profile.XBOX, FamilyAccessPolicy.Target.PASSTHROUGH_INPUT,
                XBOX_INPUT, 12, 0).allowed);
        assertEquals(FamilyAccessPolicy.Reason.WRONG_XBOX_INPUT,
                decide(FamilyTimePolicy.Profile.XBOX, FamilyAccessPolicy.Target.PASSTHROUGH_INPUT,
                        "vendor/.TvInput/HW2", 12, 0).reason);
        assertFalse(decide(FamilyTimePolicy.Profile.XBOX, FamilyAccessPolicy.Target.APP, null, 12, 0).allowed);
        assertEquals(FamilyAccessPolicy.Reason.OUTSIDE_HOURS,
                decide(FamilyTimePolicy.Profile.XBOX, FamilyAccessPolicy.Target.PASSTHROUGH_INPUT,
                        XBOX_INPUT, 22, 0).reason);
    }

    @Test public void xboxRejectsMissingConfigurationAndUnavailableInputIds() {
        FamilyAccessPolicy.Decision missing = FamilyAccessPolicy.evaluate(FamilyTimePolicy.Profile.XBOX,
                FamilyAccessPolicy.Target.PASSTHROUGH_INPUT, null, null, at(12, 0), 0, 0, false);
        assertEquals(FamilyAccessPolicy.Reason.WRONG_XBOX_INPUT, missing.reason);

        FamilyAccessPolicy.Decision unavailable = FamilyAccessPolicy.evaluate(FamilyTimePolicy.Profile.XBOX,
                FamilyAccessPolicy.Target.PASSTHROUGH_INPUT, "vendor/.TvInput/REMOVED", XBOX_INPUT,
                at(12, 0), 0, 0, false);
        assertEquals(FamilyAccessPolicy.Reason.WRONG_XBOX_INPUT, unavailable.reason);
    }

    @Test public void unknownTargetsFailClosedForChildAndXboxButAdultRemainsUnrestricted() {
        assertEquals(FamilyAccessPolicy.Reason.TARGET_NOT_ALLOWED,
                decide(FamilyTimePolicy.Profile.CHILD_TV, FamilyAccessPolicy.Target.UNKNOWN, null, 12, 0).reason);
        assertFalse(decide(FamilyTimePolicy.Profile.XBOX, FamilyAccessPolicy.Target.UNKNOWN, null, 12, 0).allowed);
        assertTrue(decide(FamilyTimePolicy.Profile.ADULT, FamilyAccessPolicy.Target.UNKNOWN, null, 23, 30).allowed);
    }

    @Test public void missingProfileRequiresSelection() {
        FamilyAccessPolicy.Decision decision = FamilyAccessPolicy.evaluate(null, FamilyAccessPolicy.Target.APP,
                null, null, at(12, 0), 0, 0, false);
        assertFalse(decision.allowed);
        assertEquals(FamilyAccessPolicy.Reason.PROFILE_REQUIRED, decision.reason);
    }
}
