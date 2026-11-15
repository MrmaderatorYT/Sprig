package com.ccs.sprig.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;
import com.ccs.sprig.R;

final class AppInformation {
    private AppInformation() {}
    static PackageInfo installed(Context context) {
        try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0); }
        catch (PackageManager.NameNotFoundException impossible) { throw new IllegalStateException(impossible); }
    }
    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    static void sendFeedback(Context context) {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + context.getString(R.string.feedback_email)));
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_subject, installed(context).versionName));
        // No captures, credentials, device identifiers or diagnostics are attached.
        open(context, intent, R.string.email_unavailable);
    }
    static void openLanguageSettings(Context context) {
        Intent intent = Build.VERSION.SDK_INT >= 33
                ? new Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:" + context.getPackageName()))
                : new Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS);
        open(context, intent, R.string.link_unavailable);
    }
    static void openSource(Context context) {
        open(context, new Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.source_code_url))), R.string.link_unavailable);
    }
    private static void open(Context context, Intent intent, int unavailable) {
        try { context.startActivity(intent); }
        catch (ActivityNotFoundException error) { Toast.makeText(context, unavailable, Toast.LENGTH_LONG).show(); }
    }
}
