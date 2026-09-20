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
import android.os.SystemClock;
import android.webkit.ValueCallback;

/**
 * Tiny foreground service. It does no playback itself — the WebView does.
 * Its jobs:
 *  - tell Android "this app is playing audio, don't kill it" (1 GB RAM phones);
 *  - show a Play/Pause button in the notification (works on the lock screen);
 *  - for the first 15 s after leaving the app, if the page paused itself,
 *    press play again automatically.
 *
 * Battery / RAM safety:
 *  - Wake lock is only renewed while audio is actually playing (10 min timeout).
 *  - After 10 minutes of silence the service stops itself.
 *  - Swiping the app away from Recents stops it immediately.
 */
public class PlaybackService extends Service {

    static final String ACTION_TOGGLE = "com.example.ytlite.TOGGLE";

    private static final String CHANNEL_ID = "ytlite_playback";
    private static final int NOTIFICATION_ID = 1;

    private static final long FAST_PHASE_MS = 15000L;
    private static final long FAST_INTERVAL_MS = 1500L;
    private static final long SLOW_INTERVAL_MS = 10000L;
    private static final long IDLE_STOP_MS = 10L * 60 * 1000;
    private static final long WAKELOCK_TIMEOUT_MS = 10L * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private long startTime;
    private long lastActive;
    private boolean userPaused;
    private boolean shownPlaying = true;
    private int press;          // id of the latest Play press (debug)
    private String dbg = "";   // debug line shown in the expanded notification

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean fast = now - startTime < FAST_PHASE_MS;
            boolean playing = isPlaying();

            if (playing) {
                lastActive = now;
                renewWakeLock();
            } else if (!userPaused && fast) {
                // Page paused itself right after we left the app: resume it.
                MainActivity.runJs(MainActivity.PLAY_JS);
            } else if (now - lastActive > IDLE_STOP_MS) {
                stopSelf();
                return;
            }

            if (playing != shownPlaying) updateNotification(playing);
            handler.postDelayed(this, fast ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateNotification(isPlaying());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startTime = SystemClock.elapsedRealtime();
        lastActive = startTime;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL_ID, "Background playback", NotificationManager.IMPORTANCE_LOW));
            }
        }

        Notification n = buildNotification(true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytlite:playback");
            wakeLock.setReferenceCounted(false);
            renewWakeLock();
        }
        handler.postDelayed(ticker, FAST_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            toggle();
        }
        return START_NOT_STICKY;
    }

    // Notification Play/Pause button.
    private void toggle() {
        if (isPlaying()) {
            userPaused = true;
            MainActivity.runJs(MainActivity.PAUSE_JS);
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, 800);
        } else {
            userPaused = false;
            lastActive = SystemClock.elapsedRealtime();
            renewWakeLock();
            playWithDebug();
        }
    }

    // Presses play in the page and reports what the page said (temporary
    // debug info, shown when the notification is expanded).
    private void playWithDebug() {
        final int id = ++press;
        dbg = "";
        MainActivity.runJs(MainActivity.PLAY_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String v) {
                if (id == press) dbg = clean(v);
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (id != press) return;
                MainActivity.runJs(MainActivity.STATUS_JS, new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        if (id != press) return;
                        dbg += " | " + clean(v);
                        updateNotification(isPlaying());
                    }
                });
            }
        }, 1200);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (id == press && !dbg.contains("|")) {
                    dbg = "no reply from page (webview "
                            + (MainActivity.hasWebView() ? "alive" : "gone") + ")";
                    updateNotification(isPlaying());
                }
            }
        }, 3000);
    }

    private static String clean(String v) {
        if (v == null) return "null";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private boolean isPlaying() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    private void renewWakeLock() {
        if (wakeLock != null) wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
    }

    private void updateNotification(boolean playing) {
        shownPlaying = playing;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(playing));
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(boolean playing) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
            builder.setPriority(Notification.PRIORITY_LOW);
        }

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent toggle = new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE);
        PendingIntent togglePi = PendingIntent.getService(this, 1, toggle, piFlags);

        String status = playing ? "Playing in background" : "Paused";
        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("YTLite")
                .setContentText(status)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(dbg.isEmpty() ? status : status + "\n" + dbg))
                .setContentIntent(openPi)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(playing ? android.R.drawable.ic_media_pause
                                   : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", togglePi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
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
