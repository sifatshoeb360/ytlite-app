package com.example.ytlite;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Tiny foreground service. It does no playback itself — the WebView does.
 * Its only job is to tell Android "this app is playing audio, don't kill
 * it" while the screen is off (important on 1 GB RAM phones).
 *
 * Battery / RAM safety:
 *  - Every 30 s it checks whether audio is still playing. After ~1 minute
 *    of silence it stops itself, removing the notification and wake lock.
 *  - The wake lock has a 10-minute timeout that is renewed only while
 *    audio is active, so it can never stay held by accident.
 *  - Swiping the app away from Recents stops it immediately.
 */
public class PlaybackService extends Service {

    private static final String CHANNEL_ID = "ytlite_playback";
    private static final int NOTIFICATION_ID = 1;
    private static final long CHECK_INTERVAL_MS = 30000L;
    private static final int MAX_IDLE_CHECKS = 2;
    private static final long WAKELOCK_TIMEOUT_MS = 10L * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private int idleChecks = 0;

    private final Runnable idleChecker = new Runnable() {
        @Override
        public void run() {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isMusicActive()) {
                idleChecks = 0;
                if (wakeLock != null) wakeLock.acquire(WAKELOCK_TIMEOUT_MS); // renew
            } else if (++idleChecks >= MAX_IDLE_CHECKS) {
                stopSelf();
                return;
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytlite:playback");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }
        handler.postDelayed(idleChecker, CHECK_INTERVAL_MS);
    }

    @SuppressWarnings("deprecation")
    private void startInForeground() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Background playback", NotificationManager.IMPORTANCE_LOW));
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification n = builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("YTLite")
                .setContentText("Playing in background")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onDestroy() {
        handler.removeCallbacks(idleChecker);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
