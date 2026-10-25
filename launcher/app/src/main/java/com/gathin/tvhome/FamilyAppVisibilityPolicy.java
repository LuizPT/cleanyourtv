package com.gathin.tvhome;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Per-profile app visibility and launch policy, independent of the launcher's global hidden list. */
final class FamilyAppVisibilityPolicy {
    private FamilyAppVisibilityPolicy() { }

    static boolean mayLaunch(FamilyTimePolicy.Profile profile, String packageName,
                             Set<String> childApprovedPackages) {
        return mayLaunch(profile, packageName, packageName != null && childApprovedPackages != null
                && childApprovedPackages.contains(packageName));
    }

    static boolean mayLaunch(FamilyTimePolicy.Profile profile, String packageName,
                             boolean childApprovalMatchesInstall) {
        if (profile == FamilyTimePolicy.Profile.ADULT) return true;
        return profile == FamilyTimePolicy.Profile.CHILD_TV
                && packageName != null && childApprovalMatchesInstall;
    }

    /** Null profile uses the child allowlist while the profile selector is shown. */
    static Set<String> hiddenPackages(FamilyTimePolicy.Profile profile,
                                      Collection<String> installedPackages,
                                      Set<String> childApprovedPackages) {
        if (profile == FamilyTimePolicy.Profile.ADULT) return Collections.emptySet();
        Set<String> hidden = new HashSet<>();
        if (installedPackages == null) return hidden;
        for (String packageName : installedPackages) {
            if (packageName == null || profile == FamilyTimePolicy.Profile.XBOX
                    || childApprovedPackages == null || !childApprovedPackages.contains(packageName)) {
                hidden.add(packageName);
            }
        }
        return hidden;
    }
}
