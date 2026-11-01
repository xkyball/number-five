package com.no5.releaseinfo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            try { ReleaseInfo.observe(context); } catch (Exception ignored) { /* fail closed: UI reports unavailable */ }
        }
    }
}
