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
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Keeps WebView playback alive while the Activity is in the background and
 * exposes reliable notification/lock-screen media controls.
 *
 * The WebView remains the actual player. This service owns only the wake lock,
 * MediaSession and retry policy needed when Android/YouTube pauses the page as
 * its window becomes invisible.
 */
public class PlaybackService extends Service {

    static final String ACTION_PLAY = "com.example.ytlite.PLAY";
    static final String ACTION_PAUSE = "com.example.ytlite.PAUSE";
    // Kept so a PendingIntent from an older installed build still works.
    static final String ACTION_TOGGLE = "com.example.ytlite.TOGGLE";

    private static final String CHANNEL_ID = "ytlite_playback";
    private static final int NOTIFICATION_ID = 1;

    private static final long FAST_PHASE_MS = 15000L;
    private static final long FAST_INTERVAL_MS = 1000L;
    private static final long SLOW_INTERVAL_MS = 10000L;
    private static final long RESUME_WINDOW_MS = 10000L;
    private static final long RESUME_RETRY_MS = 700L;
    private static final long PLAY_ATTEMPT_THROTTLE_MS = 500L;
    private static final long IDLE_STOP_MS = 10L * 60 * 1000L;
    private static final long WAKELOCK_TIMEOUT_MS = 10L * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private MediaSession mediaSession;
    private long startTime;
    private long lastActive;
    private long lastPlayAttempt;
    private long resumeUntil;
    private boolean userPaused;
    private boolean shownPlaying;

    private final Runnable resumeRunner = new Runnable() {
        @Override
        public void run() {
            if (userPaused) return;

            long now = SystemClock.elapsedRealtime();
            if (isPlaying()) {
                resumeUntil = 0L;
                lastActive = now;
                renewWakeLock();
                if (!shownPlaying) updateNotification(true);
                return;
            }
            if (now >= resumeUntil || !MainActivity.hasWebView()) {
                resumeUntil = 0L;
                updateNotification(false);
                return;
            }

            attemptPagePlay();
            handler.postDelayed(this, RESUME_RETRY_MS);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!MainActivity.hasWebView()) {
                stopSelf();
                return;
            }

            long now = SystemClock.elapsedRealtime();
            boolean fast = now - startTime < FAST_PHASE_MS;
            boolean playing = isPlaying();

            if (playing) {
                lastActive = now;
                renewWakeLock();
            } else if (!userPaused && fast) {
                // Android/YouTube can pause during the foreground-to-background
                // transition. Retry while that transition is settling.
                attemptPagePlay();
            } else if (now - lastActive > IDLE_STOP_MS) {
                stopSelf();
                return;
            }

            if (playing != shownPlaying) updateNotification(playing);
            handler.postDelayed(this, fast ? FAST_INTERVAL_MS : SLOW_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startTime = SystemClock.elapsedRealtime();
        lastActive = startTime;

        createNotificationChannel();
        createMediaSession();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytlite:playback");
            wakeLock.setReferenceCounted(false);
        }

        shownPlaying = isPlaying();
        updateSessionState(shownPlaying);
        Notification notification = buildNotification(shownPlaying);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        if (shownPlaying) renewWakeLock();

        // Run immediately rather than waiting for the first transition race.
        handler.post(ticker);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Background playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("YouTube audio playback controls");
            manager.createNotificationChannel(channel);
        }
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "YTLitePlayback");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "YouTube audio")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YT Lite")
                .build());
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                requestPlay();
            }

            @Override
            public void onPause() {
                requestPause();
            }

            @Override
            public void onStop() {
                requestPause();
                stopSelf();
            }
        });
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            requestPlay();
        } else if (ACTION_PAUSE.equals(action)) {
            requestPause();
        } else if (ACTION_TOGGLE.equals(action)) {
            if (isPlaying()) requestPause();
            else requestPlay();
        }
        return START_NOT_STICKY;
    }

    /** Notification/MediaSession Play: retry because one WebView call can be
     * dropped while Chromium is freezing or thawing its renderer. */
    private void requestPlay() {
        userPaused = false;
        lastActive = SystemClock.elapsedRealtime();
        resumeUntil = lastActive + RESUME_WINDOW_MS;
        lastPlayAttempt = 0L;
        renewWakeLock();

        handler.removeCallbacks(resumeRunner);
        attemptPagePlay();
        handler.postDelayed(resumeRunner, RESUME_RETRY_MS);
        updateNotification(isPlaying());
    }

    private void requestPause() {
        userPaused = true;
        resumeUntil = 0L;
        handler.removeCallbacks(resumeRunner);

        // Update native state first so a stale AudioManager value cannot turn
        // a Play button into another Pause command.
        MainActivity.markPagePausedByUser();
        MainActivity.runJs(MainActivity.PAUSE_JS);
        releaseWakeLock();
        updateNotification(false);
    }

    private void attemptPagePlay() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPlayAttempt < PLAY_ATTEMPT_THROTTLE_MS) return;
        lastPlayAttempt = now;
        MainActivity.requestPagePlay(null);
    }

    /** Prefer the app-specific JavaScript report. AudioManager is only a
     * compatibility fallback before PAGE_JS has sent its first event. */
    private boolean isPlaying() {
        if (MainActivity.hasPlaybackReport()) {
            return MainActivity.isPagePlaying();
        }
        AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return manager != null && manager.isMusicActive();
    }

    private void renewWakeLock() {
        if (wakeLock != null) wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void updateNotification(boolean playing) {
        shownPlaying = playing;
        updateSessionState(playing);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(playing));
    }

    private void updateSessionState(boolean playing) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f)
                .build());
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

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 0, open, pendingIntentFlags);

        String controlAction = playing ? ACTION_PAUSE : ACTION_PLAY;
        Intent control = new Intent(this, PlaybackService.class).setAction(controlAction);
        PendingIntent controlIntent = PendingIntent.getService(
                this, playing ? 2 : 1, control, pendingIntentFlags);

        String status = playing ? "Playing in background" : "Paused — tap Play to resume";
        int controlIcon = playing
                ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String controlLabel = playing ? "Pause" : "Play";

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setShowActionsInCompactView(0);
        if (mediaSession != null) style.setMediaSession(mediaSession.getSessionToken());

        return builder
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("YT Lite")
                .setContentText(status)
                .setContentIntent(openIntent)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .addAction(controlIcon, controlLabel, controlIntent)
                .setStyle(style)
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
        releaseWakeLock();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
