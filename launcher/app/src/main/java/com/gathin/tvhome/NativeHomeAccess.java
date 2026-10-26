package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Process;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

/** Requirements are checked locally; this class never grants access or changes TV settings. */
final class NativeHomeAccess {
    static final String PREFS = "native_home_trial", ENABLED = "enabled";
    static final NativeHomeSession SESSION = new NativeHomeSession();
    private static final int STOP_ERROR_NOTIFICATION = 7311;
    private static final String STOP_ERROR = "Home redirection is blocked in this process, but the "
            + "saved off choice could not be confirmed. Retry Stop before restarting the TV.";
    static final ComponentName BROKER = new ComponentName(NativeHomePolicy.STOCK_PACKAGE,
            NativeHomePolicy.STOCK_PACKAGE + ".TvContentIntentReceiver");
    private NativeHomeAccess() { }

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false);
    }

    private static boolean setEnabled(Context context, boolean enabled) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(ENABLED, enabled).commit();
    }

    static boolean usageAllowed(Context context) {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static String problem(Context context) {
        if (Build.VERSION.SDK_INT != 29) return "This trial requires Android 10.";
        UserManager users = context.getSystemService(UserManager.class);
        if (users == null || !users.isUserUnlocked()) return "Unlock the TV before starting.";
        try {
            if (!usageAllowed(context)) return "Grant Usage Access, then choose Start.";
            AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
            if (accessibility == null) return "Accessibility state could not be verified.";
            if (accessibility.isEnabled()) {
                return "This experiment is unsupported while any accessibility service is enabled. "
                        + "Do not disable accessibility features you need to use it.";
            }
            PackageManager pm = context.getPackageManager();
            PackageInfo stock = pm.getPackageInfo(NativeHomePolicy.STOCK_PACKAGE, 0);
            if (stock.getLongVersionCode() != 1010943512L) {
                return "The original Home version differs from the reviewed version.";
            }
            ActivityInfo receiver = pm.getReceiverInfo(BROKER, 0);
            if (!receiver.enabled || !receiver.exported || receiver.permission != null
                    || receiver.applicationInfo == null || !receiver.applicationInfo.enabled) {
                return "The original Home app opener is unavailable.";
            }
            ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            if (home == null || home.activityInfo == null
                    || !NativeHomePolicy.STOCK_PACKAGE.equals(home.activityInfo.packageName)
                    || !NativeHomePolicy.STOCK_HOME.equals(home.activityInfo.name)
                    || !home.activityInfo.enabled) {
                return "The original launcher must remain the enabled default Home.";
            }
            return null;
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return "The trial requirements could not be verified.";
        }
    }

    static String start(Context context) {
        synchronized (SESSION) {
            try {
                NativeHomeSession.StartRequest request = SESSION.requestStartIfIdle(() -> problem(context));
                if (request.problem != null) return request.problem;
                if (!request.queued()) return null; // Already starting/running: no new ticket or service call.
                if (!setEnabled(context, true)) {
                    return stop(context) == null ? "The trial choice could not be saved." : STOP_ERROR;
                }
                context.startForegroundService(new Intent(context, HomeNativeService.class)
                        .setAction(HomeNativeService.START).putExtra("session_ticket", request.ticket));
                return null;
            } catch (RuntimeException unavailable) {
                return stop(context) == null
                        ? "The trial could not start; original Home remains available." : STOP_ERROR;
            }
        }
    }

    static String stop(Context context) {
        boolean saved;
        synchronized (SESSION) {
            saved = SESSION.stop(() -> setEnabled(context, false));
            context.stopService(new Intent(context, HomeNativeService.class));
        }
        if (!saved) reportStopFailure(context);
        else {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(STOP_ERROR_NOTIFICATION);
        }
        return saved ? null : STOP_ERROR;
    }

    static String stopFailure() {
        return "commit_failed".equals(SESSION.stopError()) ? STOP_ERROR : null;
    }

    static NativeHomeStatus.Phase phase(Context context) {
        synchronized (SESSION) {
            return NativeHomeStatus.phase(enabled(context), SESSION.starting(), SESSION.accepting(),
                    HomeNativeService.isRunning(), HomeNativeService.clockError());
        }
    }

    @SuppressLint("NotificationPermission") // Notification posting below is restricted to SDK 29.
    private static void reportStopFailure(Context context) {
        Context app = context.getApplicationContext();
        Log.e("NativeHomeTrial", "Stop persistence failed; durable opt-out is unverified");
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, STOP_ERROR, Toast.LENGTH_LONG).show());
        // This trial is Android 10-only; recovery reporting must not request Android 13 access.
        if (Build.VERSION.SDK_INT != 29) return;
        try {
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager == null) return;
            String channel = "native_home_trial";
            manager.createNotificationChannel(new NotificationChannel(channel,
                    app.getString(R.string.native_home_title), NotificationManager.IMPORTANCE_LOW));
            PendingIntent controls = PendingIntent.getActivity(app, 2,
                    new Intent(app, HomeNativeControlActivity.class)
                            .setAction("com.gathin.tvhome.NATIVE_HOME_STOP_ERROR"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            manager.notify(STOP_ERROR_NOTIFICATION, new Notification.Builder(app, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Home trial Stop needs attention")
                    .setContentText(STOP_ERROR).setStyle(new Notification.BigTextStyle().bigText(STOP_ERROR))
                    .setContentIntent(controls).build());
        } catch (RuntimeException unavailable) {
            // The dump marker and manual UI still expose the failure even if notifications fail.
        }
    }
}
