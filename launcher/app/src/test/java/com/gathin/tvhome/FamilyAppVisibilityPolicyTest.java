package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FamilyAppVisibilityPolicyTest {
    @Test public void childVisibilityIsIndependentOfAdultVisibility() {
        Set<String> childApproved = new HashSet<>(Arrays.asList("video.child", "game.shared"));
        Set<String> installed = new HashSet<>(Arrays.asList("video.child", "game.shared", "adult.only"));
        Set<String> adultGlobalHidden = Collections.singleton("video.child");

        assertTrue(FamilyAppVisibilityPolicy.mayLaunch(
                FamilyTimePolicy.Profile.CHILD_TV, "video.child", childApproved));
        assertTrue(FamilyAppVisibilityPolicy.mayLaunch(
                FamilyTimePolicy.Profile.ADULT, "adult.only", childApproved));
        assertFalse(FamilyAppVisibilityPolicy.mayLaunch(
                FamilyTimePolicy.Profile.CHILD_TV, "adult.only", childApproved));
        assertFalse(FamilyAppVisibilityPolicy.mayLaunch(
                FamilyTimePolicy.Profile.CHILD_TV, "video.child", Collections.emptySet()));
        // The adult's global hidden preference is not an input to the per-child allowlist policy.
        assertTrue(adultGlobalHidden.contains("video.child"));
        assertTrue(FamilyAppVisibilityPolicy.mayLaunch(
                FamilyTimePolicy.Profile.CHILD_TV, "video.child", childApproved));
    }

    @Test public void allChildHiddenAppsDisappearAndXboxShowsNoAppPackages() {
        Set<String> installed = new HashSet<>(Arrays.asList("one.app", "two.app", "three.app"));
        Set<String> approved = Collections.singleton("two.app");
        assertEquals(new HashSet<>(Arrays.asList("one.app", "three.app")),
                FamilyAppVisibilityPolicy.hiddenPackages(FamilyTimePolicy.Profile.CHILD_TV,
                        installed, approved));
        assertEquals(installed,
                FamilyAppVisibilityPolicy.hiddenPackages(FamilyTimePolicy.Profile.XBOX,
                        installed, approved));
        assertEquals(Collections.emptySet(),
                FamilyAppVisibilityPolicy.hiddenPackages(FamilyTimePolicy.Profile.ADULT,
                        installed, approved));
    }

    @Test public void profileSelectorUsesChildSubsetUntilAdultAuthenticates() {
        Set<String> installed = new HashSet<>(Arrays.asList("approved.app", "not.approved"));
        Set<String> approved = Collections.singleton("approved.app");
        assertEquals(Collections.singleton("not.approved"),
                FamilyAppVisibilityPolicy.hiddenPackages(null, installed, approved));
        assertFalse(FamilyAppVisibilityPolicy.mayLaunch(null, "approved.app", approved));
        assertTrue(FamilyAppVisibilityPolicy.mayLaunch(
                FamilyTimePolicy.Profile.ADULT, "not.approved", approved));
    }
}
