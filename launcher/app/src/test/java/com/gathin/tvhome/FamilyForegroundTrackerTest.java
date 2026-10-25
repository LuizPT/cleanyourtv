package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class FamilyForegroundTrackerTest {
    @Test public void tracksAppToLauncherTransitionsAndKeepsLatestForeground() {
        FamilyForegroundTracker tracker = new FamilyForegroundTracker("video.app");
        tracker.apply(Arrays.asList(
                new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.BACKGROUND, "video.app"),
                new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.FOREGROUND, "systemui"),
                new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.FOREGROUND, "com.gathin.tvhome")));
        assertEquals("com.gathin.tvhome", tracker.foregroundPackage());
    }

    @Test public void unrelatedBackgroundEventDoesNotClearAnotherForegroundPackage() {
        FamilyForegroundTracker tracker = new FamilyForegroundTracker("video.app");
        tracker.apply(Arrays.asList(
                new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.FOREGROUND, "settings"),
                new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.BACKGROUND, "video.app")));
        assertEquals("settings", tracker.foregroundPackage());
    }

    @Test public void foregroundBackgroundWithoutReplacementBecomesUnknown() {
        FamilyForegroundTracker tracker = new FamilyForegroundTracker("video.app");
        tracker.apply(Collections.singletonList(
                new FamilyForegroundTracker.Event(FamilyForegroundTracker.Type.BACKGROUND, "video.app")));
        assertNull(tracker.foregroundPackage());
    }
}
