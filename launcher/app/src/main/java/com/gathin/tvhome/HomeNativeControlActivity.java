package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Own UI and permission-protected shell entry; exported entry does not grant any settings access. */
public final class HomeNativeControlActivity extends Activity {
    private AlertDialog dialog;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshStatus = new Runnable() {
        @Override public void run() {
            if (dialog == null || isFinishing()) return;
            renderStatus();
            statusHandler.postDelayed(this, 250); // UI-only, while visible; never queries UsageEvents.
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!applyOperation(getIntent())) return;
        dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.native_home_title)
                .setMessage(message())
                .setPositiveButton(R.string.native_home_start, null)
                .setNegativeButton(R.string.native_home_stop, null)
                .setNeutralButton(R.string.native_home_usage, null)
                .setOnCancelListener(ignored -> finish()).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                startTrial(); renderStatus();
            });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                NativeHomeAccess.stop(this); renderStatus();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try { startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
                catch (ActivityNotFoundException | SecurityException unavailable) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                }
            });
        });
        dialog.show();
        renderStatus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyOperation(intent) && dialog != null) renderStatus();
    }

    private boolean applyOperation(Intent intent) {
        String operation = intent.getStringExtra("operation");
        if ("stop".equals(operation)) NativeHomeAccess.stop(this);
        else if ("start".equals(operation)) startTrial();
        else if (operation != null) { finish(); return false; }
        return true;
    }

    private void startTrial() {
        String problem = NativeHomeAccess.start(this);
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
    }

    private String message() {
        String failure = NativeHomeAccess.stopFailure();
        if (failure != null) return getString(R.string.native_home_help) + "\n\n" + failure;
        String status;
        switch (NativeHomeAccess.phase(this)) {
            case STARTING: status = getString(R.string.native_home_starting); break;
            case RUNNING: status = getString(R.string.native_home_running); break;
            case PAUSED: status = getString(R.string.native_home_paused); break;
            case FAILED: status = getString(R.string.native_home_failed, HomeNativeService.clockError()); break;
            default: status = getString(R.string.native_home_disabled); break;
        }
        return getString(R.string.native_home_help) + "\n\n" + status;
    }

    private void renderStatus() {
        dialog.setMessage(message());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setEnabled(NativeHomeStatus.canStart(NativeHomeAccess.phase(this)));
    }

    @Override protected void onResume() {
        super.onResume();
        statusHandler.removeCallbacks(refreshStatus);
        if (dialog != null) statusHandler.post(refreshStatus);
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    @Override protected void onDestroy() {
        statusHandler.removeCallbacks(refreshStatus);
        super.onDestroy();
    }

    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(prefix + HomeNativeService.clockStatus());
        writer.println(prefix + "HomeNativeTrial enabled=" + NativeHomeAccess.enabled(this)
                + " running=" + HomeNativeService.isRunning());
        synchronized (NativeHomeAccess.SESSION) {
            writer.println(prefix + "HomeNativeTrial stopDurable="
                    + NativeHomeAccess.SESSION.stopDurable() + " stopError="
                    + NativeHomeAccess.SESSION.stopError());
        }
    }
}
