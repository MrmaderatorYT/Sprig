package com.ccs.sprig.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Local, on-demand launcher selection. No inventory export or network dependency. */
final class CaptureAppCatalog {
    static final class Entry {
        final String name, packageName;
        Entry(String name, String packageName) { this.name=name; this.packageName=packageName; }
    }
    private CaptureAppCatalog() {}
    static List<Entry> load(PackageManager manager, String ownPackage) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (ResolveInfo resolved : manager.queryIntentActivities(launcher, 0)) {
            if (resolved.activityInfo == null || resolved.activityInfo.applicationInfo == null) continue;
            String pkg = resolved.activityInfo.packageName;
            if (pkg == null || pkg.equals(ownPackage) || unique.containsKey(pkg)) continue;
            CharSequence label = resolved.activityInfo.applicationInfo.loadLabel(manager);
            unique.put(pkg, new Entry(label == null ? pkg : label.toString(), pkg));
        }
        List<Entry> choices = new ArrayList<>(unique.values());
        choices.sort(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)));
        return choices;
    }
}
