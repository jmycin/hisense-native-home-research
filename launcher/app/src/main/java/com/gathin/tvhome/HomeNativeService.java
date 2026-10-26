package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Android 10-only, opt-in observer. No key interception, accessibility, or polling while idle. */
public final class HomeNativeService extends Service {
    static final String START = "com.gathin.tvhome.NATIVE_HOME_START";
    static final String STOP = "com.gathin.tvhome.NATIVE_HOME_STOP";
    private static final String CHANNEL = "native_home_trial";
    private static volatile boolean running;
    private static volatile String clockError = "stopped";
    private final NativeHomeClock clock = new NativeHomeClock();
    private final NativeHomePolicy policy = new NativeHomePolicy();
    private final NativeHomePolicy.Foreground foreground = new NativeHomePolicy.Foreground();
    private HandlerThread thread;
    private Handler worker;
    private boolean registered;
    private long windowStartElapsed;
    private long initialDeadline, ownQueryStart, calibrationBegan;
    private Notification notification;
    private long serviceTicket = -1;

    static boolean isRunning() { return running; }
    static String clockError() { return clockError; }
    static String clockStatus() {
        synchronized (NativeHomeAccess.SESSION) {
            String error = clockError;
            boolean ready = "none".equals(error) && running && NativeHomeAccess.SESSION.accepting();
            if (!ready && "none".equals(error)) error = "stopped";
            return "HomeNativeTrial calibrated=" + ready + " clockError=" + error;
        }
    }

    private final BroadcastReceiver homeSignal = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!running || !Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())
                    || !"homekey".equals(intent.getStringExtra("reason"))) return;
            final long token = NativeHomeAccess.SESSION.homeSignal();
            if (token < 0) return;
            final long elapsed = SystemClock.elapsedRealtime();
            final long wall = System.currentTimeMillis();
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                if (!current(token)) return;
                foreground.clear();
                windowStartElapsed = elapsed;
                policy.begin(token, elapsed, wall);
                poll(token);
            });
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    // These requirements apply on Android 14+. The explicit SDK 29 guard below is mandatory:
    // this firmware-pinned trial does not declare a misleading Android 14 service type or use
    // receiver flag constants unavailable on Android 10. Sender permission still gates the receiver.
    @SuppressLint({"ForegroundServiceType", "UnspecifiedRegisterReceiverFlag"})
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            NativeHomeAccess.stop(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        long ticket = intent == null ? -1 : intent.getLongExtra("session_ticket", -1);
        if (!NativeHomeAccess.SESSION.startCurrent(ticket)) {
            if (!running && !NativeHomeAccess.SESSION.activeOrStarting()) stopSelf(startId);
            // A stale delivery must not stop a newer pending/calibrating observer. If truly idle,
            // the startId form also avoids stopping any newer command Android has already queued.
            return START_NOT_STICKY; // A Stop (or newer Start) invalidated this queued command.
        }
        if (Build.VERSION.SDK_INT != 29 || intent == null || !START.equals(intent.getAction())
                || !NativeHomeAccess.enabled(this) || NativeHomeAccess.problem(this) != null) {
            failStart(ticket, "requirements_unavailable");
            return START_NOT_STICKY;
        }
        if (!NativeHomeAccess.SESSION.claimStart(ticket)) {
            return START_NOT_STICKY; // A repeated delivery of this pending Start changes nothing.
        }
        serviceTicket = ticket; // Even destruction before worker creation must release this reservation.
        if (running || thread != null) {
            failStart(ticket, "observer_teardown_pending"); // Never reuse an older observer after Stop/new Start.
            return START_NOT_STICKY;
        }
        try {
            String prerequisite = NativeHomeClockPrerequisites.problem();
            if (prerequisite != null) {
                failStart(ticket, prerequisite);
                return START_NOT_STICKY;
            }
            NotificationManager notifications = getSystemService(NotificationManager.class);
            if (notifications == null) throw new IllegalStateException("No notification service");
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.native_home_title), NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0,
                    new Intent(this, HomeNativeControlActivity.class)
                            .setAction("com.gathin.tvhome.NATIVE_HOME_MANAGE"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, HomeNativeService.class).setAction(STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle(getString(R.string.native_home_title))
                    .setContentText(getString(R.string.native_home_notification))
                    .setContentIntent(open).setOngoing(true)
                    .addAction(new Notification.Action.Builder(null,
                            getString(R.string.native_home_stop), stop).build()).build();
            if (!NativeHomeAccess.SESSION.duringStart(ticket,
                    () -> startForeground(7310, notification))) {
                if (!NativeHomeAccess.SESSION.activeOrStarting()) stopSelf(startId);
                return START_NOT_STICKY;
            }
            clockError = "pending";
            calibrationBegan = SystemClock.elapsedRealtime();
            initialDeadline = calibrationBegan + NativeHomeClock.PUBLICATION_MS;
            ownQueryStart = Math.max(0, System.currentTimeMillis() - NativeHomeClock.OWN_LOOKBACK_MS);
            thread = new HandlerThread("NativeHomeObserver");
            thread.start();
            worker = new Handler(thread.getLooper());
            // AOSP 10 emits this broadcast as SYSTEM_UID. Ordinary direct app broadcasts cannot
            // satisfy this sender permission. Foreground verification is still required.
            registerReceiver(homeSignal, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                    "android.permission.STATUS_BAR_SERVICE", null);
            registered = true;
            worker.post(() -> observeCalibrationSentinel(ticket));
        } catch (RuntimeException unavailable) {
            failStart(ticket, "query_failed");
        }
        return START_NOT_STICKY; // No restart loop after process death.
    }

    private boolean current(long token) {
        return running && NativeHomeAccess.SESSION.current(token) && NativeHomeAccess.enabled(this);
    }

    private boolean calibrating(long ticket) {
        if (!NativeHomeAccess.SESSION.startCurrent(ticket) || !NativeHomeAccess.enabled(this)) return false;
        if (!NativeHomeClock.withinStartup(calibrationBegan, SystemClock.elapsedRealtime())) {
            failStart(ticket, "startup_timeout"); return false;
        }
        return true;
    }

    private List<NativeHomeClock.Mark> ownMarks(long from, long to) {
        if (Build.VERSION.SDK_INT < 28) throw new IllegalStateException("Own-event API unavailable");
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        if (usage == null || !NativeHomeAccess.usageAllowed(this))
            throw new IllegalStateException("Usage Access unavailable");
        UsageEvents history = usage.queryEventsForSelf(Math.max(0,
                Math.min(from, System.currentTimeMillis())), to);
        if (history == null) throw new IllegalStateException("Own events unavailable");
        List<NativeHomeClock.Mark> marks = new ArrayList<>();
        UsageEvents.Event event = new UsageEvents.Event(); int count = 0;
        while (history.hasNextEvent()) {
            if (++count > 2000 || !history.getNextEvent(event))
                throw new IllegalStateException("Own events incomplete");
            if (getPackageName().equals(event.getPackageName())
                    && HomeNativeService.class.getName().equals(event.getClassName())
                    && (event.getEventType() == NativeHomeClock.START
                        || event.getEventType() == NativeHomeClock.STOP))
                marks.add(new NativeHomeClock.Mark(event.getTimeStamp(), event.getEventType()));
        }
        return marks;
    }

    private void observeCalibrationSentinel(long ticket) {
        if (!calibrating(ticket)) return;
        try {
            if (SystemClock.elapsedRealtime() >= initialDeadline) {
                failStart(ticket, "publication_timeout"); return;
            }
            List<NativeHomeClock.Mark> marks = ownMarks(ownQueryStart,
                    System.currentTimeMillis() + NativeHomeClock.DRIFT_BOUND + 1);
            if (!calibrating(ticket)) return;
            if (SystemClock.elapsedRealtime() >= initialDeadline) {
                failStart(ticket, "publication_timeout"); return;
            }
            if (clock.observe(marks, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                worker.postDelayed(() -> refreshCalibration(ticket), NativeHomeClock.SEPARATION_MS);
            } else if (!"pending".equals(clock.error())) failStart(ticket, clock.error());
            else worker.postDelayed(() -> observeCalibrationSentinel(ticket), 100);
        } catch (RuntimeException failure) { failStart(ticket, "query_failed"); }
    }

    @SuppressLint("ForegroundServiceType") // Private startup path after the exact SDK29 guard.
    private void refreshCalibration(long ticket) {
        if (!calibrating(ticket)) return;
        try {
            List<NativeHomeClock.Mark> marks = ownMarks(ownQueryStart,
                    System.currentTimeMillis() + NativeHomeClock.DRIFT_BOUND + 1);
            if (!calibrating(ticket)) return;
            if (!clock.canRefresh(marks, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                failStart(ticket, clock.error()); return;
            }
            boolean performed = NativeHomeAccess.SESSION.duringStart(ticket, () -> {
                if (!calibrating(ticket)) return;
                // Retain the same notification and service; only our own foreground state changes.
                stopForeground(false);
                SystemClock.sleep(2); // Worker-only startup gap; same monitor keeps Stop ordered.
                long before = SystemClock.elapsedRealtime();
                startForeground(7310, notification);
                long after = SystemClock.elapsedRealtime();
                clock.refreshBracket(before, after, System.currentTimeMillis());
            });
            if (!performed || !calibrating(ticket)) return;
            if (!"pending".equals(clock.error())) { failStart(ticket, clock.error()); return; }
            pollCalibrationAnchor(ticket);
        } catch (RuntimeException failure) { failStart(ticket, "query_failed"); }
    }

    private void pollCalibrationAnchor(long ticket) {
        if (!calibrating(ticket)) return;
        try {
            List<NativeHomeClock.Mark> marks = ownMarks(ownQueryStart,
                    System.currentTimeMillis() + NativeHomeClock.DRIFT_BOUND + 1);
            if (!calibrating(ticket)) return;
            if (clock.calibrate(marks, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                NativeHomeAccess.SESSION.duringStart(ticket, () -> {
                    running = calibrating(ticket) && NativeHomeAccess.SESSION.activate(ticket);
                    if (running) clockError = "none";
                });
            } else if (!"pending".equals(clock.error())) failStart(ticket, clock.error());
            else worker.postDelayed(() -> pollCalibrationAnchor(ticket), 100);
        } catch (RuntimeException failure) { failStart(ticket, "query_failed"); }
    }

    private boolean confirmClock() {
        return clock.confirm(ownMarks(clock.ownBegin(System.currentTimeMillis()), clock.ownEnd()));
    }

    private void failStart(long ticket, String reason) {
        NativeHomeAccess.SESSION.duringStart(ticket, () -> failClock(reason));
    }

    private void failCurrent(long token, String reason) {
        NativeHomeAccess.SESSION.duringCurrent(token, () -> failClock(reason));
    }

    /** Only called inside the matching session barrier; an old worker cannot stop a new session. */
    private void failClock(String reason) {
        clockError = "pending".equals(reason) ? "ambiguous_anchor" : reason;
        NativeHomeAccess.stop(this);
        stopSelf();
    }

    private void poll(long token) {
        if (!current(token)) return;
        try {
            if (!policy.windowOpen(token, SystemClock.elapsedRealtime(), System.currentTimeMillis())) {
                foreground.clear();
                return;
            }
            if (NativeHomeAccess.problem(this) != null) {
                failCurrent(token, "requirements_unavailable");
                return;
            }
            if (!confirmClock()) { failCurrent(token, clock.error()); return; }
            List<NativeHomePolicy.Event> events = refreshForeground();
            if (!confirmClock()) { failCurrent(token, clock.error()); return; }
            if (!current(token)) return;
            NativeHomePolicy.Decision decision = policy.sample(token, SystemClock.elapsedRealtime(),
                    System.currentTimeMillis(), NativeHomeAccess.usageAllowed(this), foreground, events);
            if (decision == NativeHomePolicy.Decision.WAIT) {
                worker.postDelayed(() -> poll(token), NativeHomePolicy.STABLE_MS);
            } else if (decision == NativeHomePolicy.Decision.SEND) {
                // One attempt only, through the version-pinned stock app opener. No launch fallback.
                // Stop invalidates the token synchronously under this same dispatch barrier.
                NativeHomeAccess.SESSION.dispatch(token,
                        () -> current(token) && NativeHomeAccess.problem(this) == null
                                && policy.timeValid(SystemClock.elapsedRealtime(), System.currentTimeMillis()),
                        () -> sendBroadcast(new Intent("com.google.android.tvlauncher.action.VIEW")
                                .setComponent(NativeHomeAccess.BROKER)
                                .setData(Uri.parse("tv://app/" + getPackageName()))));
            }
            if (decision != NativeHomePolicy.Decision.WAIT) foreground.clear();
        } catch (RuntimeException unavailable) {
            // Unknown permissions/data or an unavailable broker leave the original Home available.
            failCurrent(token, "query_failed");
        }
    }

    private List<NativeHomePolicy.Event> refreshForeground() {
        long from = clock.queryBegin(windowStartElapsed, System.currentTimeMillis());
        long to = clock.queryEnd(SystemClock.elapsedRealtime());
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        if (usage == null || !NativeHomeAccess.usageAllowed(this)) {
            throw new IllegalStateException("Usage Access unavailable");
        }
        UsageEvents history = usage.queryEvents(from, to);
        if (history == null) throw new IllegalStateException("Usage events unavailable");
        List<NativeHomePolicy.Event> recent = new ArrayList<>();
        UsageEvents.Event event = new UsageEvents.Event();
        int count = 0;
        foreground.clear();
        while (history.hasNextEvent()) {
            if (++count > 20000 || !history.getNextEvent(event)) {
                throw new IllegalStateException("Usage events incomplete");
            }
            NativeHomeClock.Bounds bounds = clock.elapsedBounds(event.getTimeStamp());
            NativeHomePolicy.Event state = new NativeHomePolicy.Event(bounds.first, bounds.last,
                    event.getEventType(), event.getPackageName(), event.getClassName());
            foreground.accept(state);
            recent.add(state);
        }
        return recent;
    }

    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println("HomeNativeTrial enabled=" + NativeHomeAccess.enabled(this) + " running=" + running);
        writer.println(clockStatus());
    }

    @Override public void onDestroy() {
        running = false;
        if ("none".equals(clockError) || "pending".equals(clockError)) clockError = "stopped";
        NativeHomeAccess.SESSION.serviceDestroyed(serviceTicket);
        if (registered) unregisterReceiver(homeSignal);
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        stopForeground(true);
        super.onDestroy();
    }
}
