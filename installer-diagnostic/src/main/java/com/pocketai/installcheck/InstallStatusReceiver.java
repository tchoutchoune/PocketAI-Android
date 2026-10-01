package com.pocketai.installcheck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

public final class InstallStatusReceiver extends BroadcastReceiver {
    static final String ACTION = "com.pocketai.installcheck.INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) return;
        SharedPreferences prefs = MainActivity.prefs(context);
        int sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (sessionId < 0 || sessionId != prefs.getInt("session", -1)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int legacy = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Integer.MIN_VALUE);
        String report = InstallResult.format(status, legacy,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        String otherPackage = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME);
        if (otherPackage != null && !otherPackage.isBlank()) {
            report += "\nAutre application signalée par Android : " + otherPackage;
        }
        SharedPreferences.Editor edit = prefs.edit().putString("result", report);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirmation != null
                    && MainActivity.CONFIRM_ACTION.equals(confirmation.getAction())) {
                // Stored only in this app's private preferences. A foreground user action starts it.
                edit.putString("confirmation", confirmation.toUri(Intent.URI_INTENT_SCHEME));
            } else {
                edit.remove("confirmation").putString("result", report
                        + "\nAndroid n'a pas fourni de demande de confirmation exploitable.");
            }
        } else {
            edit.remove("confirmation").putInt("session", -1);
        }
        edit.apply();
        MainActivity.statusArrived();
    }
}
