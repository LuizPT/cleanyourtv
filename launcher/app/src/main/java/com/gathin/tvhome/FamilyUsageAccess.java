package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;

/** Checks the user-granted Usage Access app-op; the manifest declaration alone is not enough. */
final class FamilyUsageAccess {
    private FamilyUsageAccess() { }

    static boolean isGranted(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        try {
            return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException deniedOrUnsupported) {
            return false;
        }
    }
}
