package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.UserManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Local Hisense build: redirect only the configured stock HOME window.
 * This service never filters keys or retrieves view content. It is opt-in through Accessibility;
 * the stock package stays enabled and remains the default Home. Disable this service before recovery.
 */
public class HomeKeyService extends AccessibilityService {
    private static final String TAG = "HomeRedirect";
    private BroadcastReceiver unlockReceiver;
    private String stockPackage = "";
    private String stockClass = "";
    private HomeRedirect.Gate gate = new HomeRedirect.Gate();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        gate = new HomeRedirect.Gate();
        // Metadata requests no events before the user is unlocked and the exact target is checked.
        UserManager users = getSystemService(UserManager.class);
        if (users.isUserUnlocked()) {
            loadConfig();
            return;
        }
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopWaitingForUnlock();
                loadConfig();
            }
        };
        registerReceiver(unlockReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        if (users.isUserUnlocked()) {
            stopWaitingForUnlock();
            loadConfig();
        }
    }

    private void stopWaitingForUnlock() {
        if (unlockReceiver == null) return;
        unregisterReceiver(unlockReceiver);
        unlockReceiver = null;
    }

    @Override
    public void onDestroy() {
        stopWaitingForUnlock();
        super.onDestroy();
    }

    private void loadConfig() {
        Config config = Config.load(this);
        String expectedClass = getString(R.string.stock_home_redirect_class).trim();
        stockPackage = "";
        stockClass = "";
        // Class is a compiled local resource, not a theme override. Require the exact class to
        // actually claim HOME in the configured package before subscribing to any window events.
        if (!config.stockHomeRedirect.isEmpty() && !expectedClass.isEmpty()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(config.stockHomeRedirect);
            try {
                for (ResolveInfo ri : getPackageManager().queryIntentActivities(home,
                        0)) {
                    if (ri.activityInfo != null
                            && config.stockHomeRedirect.equals(ri.activityInfo.packageName)
                            && expectedClass.equals(ri.activityInfo.name)) {
                        stockPackage = config.stockHomeRedirect;
                        stockClass = expectedClass;
                        break;
                    }
                }
            } catch (SecurityException unavailable) {
                Log.w(TAG, "Cannot verify stock HOME; redirect remains inactive", unavailable);
            }
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        boolean enabled = HomeRedirect.configured(stockPackage, stockClass);
        info.eventTypes = enabled ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
        info.packageNames = enabled ? new String[]{stockPackage} : new String[0];
        // Defensive only: the local XML also removes the flag AND the key-filter capability.
        info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !HomeRedirect.matches(event.getEventType(), stockPackage, stockClass,
                event.getPackageName(), event.getClassName())) return;
        if (!gate.allow(SystemClock.elapsedRealtime())) return;
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            // Stop subscribing after a launch failure; opt-in service reconnect is required to retry.
            gate.fail();
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.eventTypes = 0;
                info.packageNames = new String[0];
                setServiceInfo(info);
            }
            Log.w(TAG, "Cannot open CleanYourTV; redirect stopped and stock Home remains available", unavailable);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
