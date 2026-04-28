package com.winlator.cmod.runtime.system;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.winlator.cmod.R;
import com.winlator.cmod.app.shell.UnifiedActivity;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that keeps the WinNative process alive while a wine
 * session is in the background or while a component download/install is
 * running. Without it, Android can reap the app process when the screen is
 * locked, taking the wine container (and any in-flight download) with it.
 *
 * Reasons are reference-counted via static helpers. The service stops itself
 * once no reasons remain. On task removal (user swipe-away) it does a
 * defensive wine cleanup and lets the process exit, matching the previous
 * "swipe = close" behaviour.
 */
public class SessionKeepAliveService extends Service {
    private static final String TAG = "SessionKeepAlive";

    private static final String CHANNEL_ID = "winnative_session_keepalive";
    private static final int NOTIFICATION_ID = 0xC0DE;

    private static final String ACTION_GAME_START = "com.winlator.cmod.action.SESSION_GAME_START";
    private static final String ACTION_GAME_STOP = "com.winlator.cmod.action.SESSION_GAME_STOP";
    private static final String ACTION_DL_START = "com.winlator.cmod.action.SESSION_DL_START";
    private static final String ACTION_DL_STOP = "com.winlator.cmod.action.SESSION_DL_STOP";
    private static final String ACTION_REFRESH = "com.winlator.cmod.action.SESSION_REFRESH";

    private static final String EXTRA_TAG = "tag";

    private static final AtomicBoolean gameActive = new AtomicBoolean(false);
    private static final HashSet<String> activeDownloads = new HashSet<>();
    private static final AtomicBoolean serviceRunning = new AtomicBoolean(false);

    public static void startGameSession(Context ctx) {
        if (ctx == null) return;
        if (gameActive.compareAndSet(false, true)) {
            sendCommand(ctx, ACTION_GAME_START, null);
        }
    }

    public static void stopGameSession(Context ctx) {
        if (ctx == null) return;
        if (gameActive.compareAndSet(true, false)) {
            sendCommand(ctx, ACTION_GAME_STOP, null);
        }
    }

    public static void startDownload(Context ctx, String tag) {
        if (ctx == null) return;
        String key = tag == null ? "default" : tag;
        boolean added;
        synchronized (activeDownloads) {
            added = activeDownloads.add(key);
        }
        if (added) {
            sendCommand(ctx, ACTION_DL_START, key);
        }
    }

    public static void stopDownload(Context ctx, String tag) {
        if (ctx == null) return;
        String key = tag == null ? "default" : tag;
        boolean removed;
        synchronized (activeDownloads) {
            removed = activeDownloads.remove(key);
        }
        if (removed) {
            sendCommand(ctx, ACTION_DL_STOP, key);
        }
    }

    public static boolean isGameSessionActive() {
        return gameActive.get();
    }

    private static boolean hasReason() {
        if (gameActive.get()) return true;
        synchronized (activeDownloads) {
            return !activeDownloads.isEmpty();
        }
    }

    private static void sendCommand(Context ctx, String action, @Nullable String tag) {
        Context app = ctx.getApplicationContext();
        Intent intent = new Intent(app, SessionKeepAliveService.class);
        intent.setAction(action);
        if (tag != null) intent.putExtra(EXTRA_TAG, tag);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to send command " + action, e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always promote to foreground first so Android does not consider
        // the start a violation (and so the notification reflects current
        // reasons), even if the command immediately tells us to stop.
        ensureForeground();
        serviceRunning.set(true);

        if (!hasReason()) {
            Log.d(TAG, "No active reason; stopping keep-alive service");
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
        }
        return START_NOT_STICKY;
    }

    private void ensureForeground() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to startForeground", e);
        }
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stopForeground", e);
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WinNative session keep-alive",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(
                "Keeps WinNative running in the background so a paused game session or "
                        + "an active component download is not interrupted by screen lock.");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        boolean game = gameActive.get();
        boolean dl;
        synchronized (activeDownloads) {
            dl = !activeDownloads.isEmpty();
        }
        String content;
        if (game && dl) {
            content = "Session paused — downloads continuing in background";
        } else if (game) {
            content = "Game session is paused in the background";
        } else if (dl) {
            content = "Downloading components in the background";
        } else {
            content = "WinNative is running in the background";
        }

        Intent openIntent = new Intent(this, UnifiedActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.common_ui_app_name))
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.i(TAG, "Task removed (user swipe). Tearing down session and exiting process.");

        // Clear reasons so any subsequent re-entry will not keep us alive.
        gameActive.set(false);
        synchronized (activeDownloads) {
            activeDownloads.clear();
        }

        // Give the activity's own onDestroy → performForcedSessionCleanup a
        // chance to run first; then defensively clean any wine processes that
        // might still be alive, and exit the process so swipe behaves like the
        // pre-existing "swipe-away closes everything" flow.
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try {
                ProcessHelper.terminateSessionProcessesAndWait(1500, true);
                ProcessHelper.drainDeadChildren("session keep-alive task removed");
            } catch (Throwable t) {
                Log.w(TAG, "Defensive wine cleanup on task removal failed", t);
            }
            stopForegroundCompat();
            stopSelf();
            serviceRunning.set(false);
            // Match the previous swipe behaviour: actually exit the process.
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 1500L);
    }

    @Override
    public void onDestroy() {
        serviceRunning.set(false);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
