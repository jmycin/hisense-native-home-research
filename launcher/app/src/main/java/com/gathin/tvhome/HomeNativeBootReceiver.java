package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Guarded boot restart for a previously opted-in TV; does not reset the OS-recovery budget. */
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
