package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot is the only automatic restart path, and only for a previously opted-in compatible TV. */
public final class HomeNativeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !NativeHomeAccess.enabled(context)) return;
        if (NativeHomeAccess.problem(context) != null) {
            NativeHomeAccess.stop(context);
            return;
        }
        NativeHomeAccess.start(context);
    }
}
