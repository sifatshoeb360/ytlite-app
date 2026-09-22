package com.example.ytlite;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.io.ByteArrayInputStream;
import java.io.File;

/**
 * Single-Activity, single-WebView "app" for very low-RAM / low-storage
 * devices (e.g. Redmi Go, Android 8.1 Go edition).
 *
 * What this version adds on top of the plain wrapper:
 *  1. Faster opening: the HTTP cache is only trimmed when it grows past
 *     MAX_CACHE_BYTES, instead of being wiped every time the app is
 *     backgrounded (that wipe forced a full re-download every launch).
 *  2. Ad reduction: known ad hosts are blocked, and a tiny script hides
 *     ad blocks, clicks "Skip", and fast-forwards video ads.
 *  3. Background audio: the page is told it is always "visible", the
 *     WebView is not paused while audio plays, and a small foreground
 *     service (PlaybackService) keeps the process alive with the screen off.
 *
 * Still zero third-party libraries.
 */
public class MainActivity extends Activity {

    private static final String HOME_URL = "https://m.youtube.com";

    // Only these hosts are allowed to load *inside* the WebView.
    private static final String[] ALLOWED_HOSTS = {
            "m.youtube.com",
            "www.youtube.com",
            "youtube.com",
            "youtu.be",
            "yewtu.be",
            "accounts.google.com",
            "consent.google.com"
    };

    // Ad networks: requests to these hosts (and subdomains) are dropped.
    private static final String[] BLOCKED_HOSTS = {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com"
    };

    // Trim the cache only once it is bigger than this (bytes).
    private static final long MAX_CACHE_BYTES = 40L * 1024 * 1024;

    private static final int REQ_NOTIFICATIONS = 1;

    // AudioManager.isMusicActive() is global and may lag behind WebView by a
    // few moments. The JavaScript bridge below gives us the page's real media
    // state; this short grace period also covers the instant in which Android
    // backgrounds the page before Activity.onPause() is delivered.
    private static final long RECENT_PLAYBACK_GRACE_MS = 3000L;

    // Sent to the page when we leave the foreground / come back.
    private static final String BG_ON_JS =
            "(function(){window.__ytlBg=Date.now();window.__ytlN=0;"
            + "if(window.__ytlNudge)window.__ytlNudge()})();";
    private static final String BG_OFF_JS = "window.__ytlBg=0;";

    // Used by PlaybackService (notification controls + auto-resume).
    static final String PLAY_JS =
            "(function(){window.__ytlBg=Date.now();window.__ytlN=0;"
            + "window.__ytlUserPause=0;window.__ytlErr='';"
            + "var p=document.querySelector('.html5-video-player'),v=document.querySelector('video');"
            // Recover the media pipeline even if playback has already paused.
            // Do this before play(): on affected WebViews play alone can stall.
            + "if(v&&v.paused&&window.__ytlNudge)window.__ytlNudge(true);"
            + "try{if(p&&typeof p.playVideo==='function')p.playVideo()}"
            + "catch(e){window.__ytlErr=e.name}"
            // Some mobile players expose playVideo but silently ignore it.
            + "try{if(v&&v.paused){var q=v.play();"
            + "if(q&&q.catch)q.catch(function(e){window.__ytlErr=e.name})}}"
            + "catch(e){window.__ytlErr=e.name}"
            + "if(window.__ytlReport)window.__ytlReport();"
            + "return window.__ytlErr})();";
    static final String PAUSE_JS =
            "(function(){window.__ytlBg=0;window.__ytlUserPause=Date.now();"
            + "var p=document.querySelector('.html5-video-player'),v=document.querySelector('video');"
            + "if(p&&typeof p.pauseVideo==='function')p.pauseVideo();else if(v)v.pause()})();";

    private static WebView sWebView; // set while the Activity exists
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean sPagePlaying;
    private static volatile boolean sHasPlaybackReport;
    private static volatile boolean sExplicitlyStopped;
    private static volatile long sLastPagePlayingAt;

    /** Receives lightweight media events from PAGE_JS without polling WebView. */
    private static final class PlaybackBridge {
        @JavascriptInterface
        public void onPlaybackState(boolean playing, boolean explicitlyStopped) {
            sHasPlaybackReport = true;
            sPagePlaying = playing;
            if (playing) {
                sLastPagePlayingAt = SystemClock.elapsedRealtime();
                sExplicitlyStopped = false;
            } else if (explicitlyStopped) {
                sExplicitlyStopped = true;
            }
        }
    }

    static void runJs(String js) {
        runJs(js, null);
    }

    static void runJs(final String js, final ValueCallback<String> callback) {
        final WebView w = sWebView;
        if (w == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (w != sWebView) return;
                try {
                    w.evaluateJavascript(js, callback);
                } catch (RuntimeException ignored) {
                    // The Activity/WebView was destroyed while this was queued.
                }
            }
        });
    }

    // A background WebView can have its timers suspended. Wake it before a
    // notification/lock-screen Play command and then execute the command.
    static void requestPagePlay(final ValueCallback<String> callback) {
        sExplicitlyStopped = false;
        final WebView w = sWebView;
        if (w == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (w != sWebView) return;
                try {
                    if (w instanceof PlaybackWebView) {
                        ((PlaybackWebView) w).setBackgroundPlayback(true);
                    }
                    w.onResume();
                    w.evaluateJavascript(PLAY_JS, callback);
                } catch (RuntimeException ignored) {
                    // Best effort: the renderer may have gone away.
                }
            }
        });
    }

    static void markPagePausedByUser() {
        sPagePlaying = false;
        sHasPlaybackReport = true;
        sExplicitlyStopped = true;
    }

    static boolean isPagePlaying() {
        return sPagePlaying;
    }

    static boolean hasPlaybackReport() {
        return sHasPlaybackReport;
    }

    static boolean wasPagePlayingRecently() {
        long age = SystemClock.elapsedRealtime() - sLastPagePlayingAt;
        return !sExplicitlyStopped && sLastPagePlayingAt > 0
                && age >= 0 && age <= RECENT_PLAYBACK_GRACE_MS;
    }

    static boolean hasWebView() {
        return sWebView != null;
    }

    private static void resetPagePlaybackState() {
        sPagePlaying = false;
        sHasPlaybackReport = false;
        sExplicitlyStopped = false;
        sLastPagePlayingAt = 0L;
    }

    // Injected into every page. Compact on purpose (runs on a weak CPU).
    //  - Makes the page believe it is always visible, so YouTube does not
    //    pause playback when the screen turns off.
    //  - While we are in background mode (window.__ytlBg set by the app),
    //    ignores pause() calls made by the page itself.
    //  - Gives every new video one tiny seek after media becomes seekable
    //    (same effect as dragging the progress bar, which was observed to
    //    make background audio work).
    //  - Hides ad containers with CSS.
    //  - During a video ad: clicks Skip, mutes, speeds up and jumps to end.
    // YouTube changes its markup from time to time, so the selectors below
    // may need updating occasionally.
    private static final String PAGE_JS =
            "!function(){if(!window.__ytl){window.__ytl=1;try{[document,Document.prototype].forEach(function(e){["
            + "\"hidden\",\"webkitHidden\"].forEach(function(t){Object.defineProperty(e,t,{configurable:!0,get:function"
            + "(){return!1}})}),[\"visibilityState\",\"webkitVisibilityState\"].forEach(function(t){Object.defineProper"
            + "ty(e,t,{configurable:!0,get:function(){return\"visible\"}})})}),document.hasFocus=function(){return!0}"
            + "}catch(e){}var e=function(e){e.target!==window&&e.target!==document||e.stopImmediatePropagation()};["
            + "\"visibilitychange\",\"webkitvisibilitychange\",\"blur\",\"pagehide\",\"freeze\"].forEach(function(t){window.a"
            + "ddEventListener(t,e,!0),document.addEventListener(t,e,!0)});var t=HTMLMediaElement.prototype.pause;H"
            + "TMLMediaElement.prototype.pause=function(){if(!window.__ytlBg||window.__ytlUserPause)return t.apply("
            + "this,arguments)};var n=0,i=!1,o=1,r=\"\",d=\"\";window.__ytlReport=function(){u(document.querySelector(\""
            + "video\"),!1)},window.__ytlNudge=function(recover){var p=document.querySelector('.html5-video-player')"
            + ",v=document.querySelector('video');try{if(!v||v.ended||v.seeking||v.readyState<2||(!recover&&v.pause"
            + "d)||window.__ytlUserPause||(p&&p.classList.contains('ad-showing')))return false;var from=Number(v.cu"
            + "rrentTime),ranges=v.seekable;if(!isFinite(from)||!ranges||!ranges.length)return false;var target=fro"
            + "m;for(var j=0;j<ranges.length;j++){var low=ranges.start(j)+.05,high=ranges.end(j)-.05;if(from>=range"
            + "s.start(j)&&from<=ranges.end(j)&&high>low){target=from+.25<=high?from+.25:from-.25>=low?from-.25:fro"
            + "m;break;}}if(Math.abs(target-from)<.01)return false;if(p&&typeof p.seekTo==='function'){try{p.seekTo"
            + "(target,true)}catch(e){v.currentTime=target}}else v.currentTime=target;return true;}catch(e){return "
            + "false}},document.addEventListener(\"play\",m,!0),document.addEventListener(\"playing\",m,!0),document.ad"
            + "dEventListener(\"pause\",function(e){var t=e.target;t&&\"VIDEO\"===t.tagName&&(u(t,!1),!window.__ytlBg||"
            + "t.ended||Date.now()-window.__ytlBg>1e4||(window.__ytlN=(window.__ytlN||0)+1)>8||setTimeout(function("
            + "){if(!window.__ytlBg||window.__ytlUserPause||t.ended||t!==document.querySelector(\"video\"))return;try"
            + "{var e=t.play();e&&e.catch&&e.catch(function(){})}catch(e){}},150))},!0),document.addEventListener(\""
            + "ended\",function(e){u(e.target,!0)},!0),y(),setInterval(y,400)}function u(e,t){if(e&&\"VIDEO\"===e.tagN"
            + "ame){var n=!e.paused&&!e.ended,i=!n&&(t||!window.__ytlBg||Date.now()-(window.__ytlUserPause||0)<1500"
            + "),o=(n?\"1\":\"0\")+(i?\"1\":\"0\");if(o!==r){r=o;try{window.YTLiteBridge&&window.YTLiteBridge.onPlaybackSta"
            + "te(n,i)}catch(e){}}}}function l(e,t){try{var n=e&&\"function\"==typeof e.getVideoData&&e.getVideoData("
            + ");if(n&&n.video_id)return n.video_id}catch(e){}return location.pathname+location.search+\"|\"+(isFinit"
            + "e(t.duration)?Math.floor(10*t.duration):\"live\")}function s(e,t){if(!(!t||t.paused||t.ended||e&&e.cla"
            + "ssList.contains(\"ad-showing\"))){var n=l(e,t);if(window.__ytlPrimed!==n&&d!==n){d=n;var i=0;setTimeou"
            + "t(function e(){var t=document.querySelector(\".html5-video-player\"),o=document.querySelector(\"video\")"
            + ";o&&l(t,o)===n?window.__ytlNudge()?(window.__ytlPrimed=n,d=\"\"):++i<5?setTimeout(e,500):d=\"\":d=\"\"},50"
            + "0)}}}function y(){if(!document.getElementById(\"ytl-css\")){var e=document.head||document.documentElem"
            + "ent;if(e){var t=document.createElement(\"style\");t.id=\"ytl-css\",t.textContent=\"ytm-promoted-sparkles-"
            + "web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-ad-slot-renderer,ad-slot-rend"
            + "erer,ytm-brand-video-singleton-renderer,#player-ads,.ytp-ad-overlay-container,.ytp-ad-image-overlay{"
            + "display:none!important}\",e.appendChild(t)}}var r=document.querySelector(\".html5-video-player\"),a=doc"
            + "ument.querySelector(\"video\");if(r&&a)if(u(a,!1),a.paused||s(r,a),r.classList.contains(\"ad-showing\"))"
            + "{var d=document.querySelector(\".ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button\");"
            + "d&&d.click(),n||(i=a.muted,o=a.playbackRate,n=1),a.muted=!0,a.playbackRate=16,isFinite(a.duration)&&"
            + "a.duration>0&&(a.currentTime=a.duration)}else n&&(a.playbackRate=o,a.muted=i,n=0)}function m(e){var "
            + "t=e.target,n=document.querySelector(\".html5-video-player\");t&&\"VIDEO\"===t.tagName&&(window.__ytlUser"
            + "Pause=0,u(t,!1),s(n,t))}}();";

    private PlaybackWebView webView;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;

    // State kept while a <video> element is in native fullscreen mode
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int originalOrientation;

    // True if audio was playing when we left the foreground.
    private boolean keepPlaying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        resetPagePlaybackState();
        sWebView = webView;

        setupWebView();
        askNotificationPermissionIfNeeded();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    // Android 13+ only: lets the "playing in background" notification show.
    // Playback works even if the user says no.
    private void askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQ_NOTIFICATIONS);
        }
    }

    private void setupWebView() {
        // Hardware layer -> smooth <video> playback / scrolling on weak GPUs.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // Do not let an invisible renderer lose its priority while the
        // foreground playback service is keeping audio alive.
        if (Build.VERSION.SDK_INT >= 26) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false);
        }

        // Exposes only one boolean state callback; no page content is passed
        // into native code. This is installed before any URL is loaded.
        webView.addJavascriptInterface(new PlaybackBridge(), "YTLiteBridge");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        settings.setUserAgentString(settings.getUserAgentString() + " YTLite/1.1 (Android Go)");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();

                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = uri.getHost();
                    if (host != null && isAllowedHost(host)) {
                        return false; // let the WebView load it normally
                    }
                    openExternally(url);
                    return true;
                }

                // intent://, market://, vnd.youtube:// ... swallow and stay here.
                openExternally(url);
                return true;
            }

            // Drop requests to ad networks before they touch the network.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                if (isBlocked(request.getUrl())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                resetPagePlaybackState();
                view.evaluateJavascript(PAGE_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                view.evaluateJavascript(PAGE_JS, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (newProgress > 0 && newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            // --- Fullscreen <video> handling ---
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                originalOrientation = getRequestedOrientation();

                webView.setVisibility(View.GONE);
                fullscreenContainer.setVisibility(View.VISIBLE);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;

                webView.setVisibility(View.VISIBLE);
                fullscreenContainer.setVisibility(View.GONE);
                fullscreenContainer.removeView(customView);
                customView = null;

                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }

                setRequestedOrientation(originalOrientation);
                showSystemUi();
            }
        });
    }

    private boolean isAllowedHost(String host) {
        for (String allowed : ALLOWED_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlocked(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        for (String blocked : BLOCKED_HOSTS) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                return true;
            }
        }
        String path = uri.getPath();
        return path != null && path.startsWith("/pagead/");
    }

    private void openExternally(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            // No app / browser can handle it — ignore.
        }
    }

    private void hideSystemUi() {
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showSystemUi() {
        //noinspection deprecation
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // Hardware back key: exit fullscreen first, else WebView history, else finish.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                webView.getWebChromeClient().onHideCustomView();
                return true;
            }
            if (webView.canGoBack()) {
                webView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    // --- Lifecycle: background audio ------------------------------------

    private boolean isAudioPlaying() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    private void startPlaybackService() {
        try {
            Intent i = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (RuntimeException e) {
            // System refused to start it right now. Not fatal: the app
            // just won't keep playing with the screen off this time.
        }
    }

    private void stopPlaybackService() {
        stopService(new Intent(this, PlaybackService.class));
    }

    private boolean shouldKeepPlaying() {
        if (sExplicitlyStopped) return false;
        if (isPagePlaying() || wasPagePlayingRecently()) return true;
        // Fallback for an old/broken WebView that did not deliver a bridge
        // event. Once the page reports state, prefer that app-specific state
        // over AudioManager's device-wide isMusicActive() value.
        return !hasPlaybackReport() && isAudioPlaying();
    }

    void prepareBackgroundPlayback() {
        if (webView == null || keepPlaying || isFinishing() || !shouldKeepPlaying()) return;

        // This must run before super.onPause(): by the time onStop() arrives,
        // newer Android versions may reject a foreground-service start.
        keepPlaying = true;
        webView.setBackgroundPlayback(true);
        webView.onResume();
        webView.evaluateJavascript(BG_ON_JS, null);
        startPlaybackService();
    }

    @Override
    protected void onUserLeaveHint() {
        // Home/Recents gives us this early callback, before YouTube gets a
        // chance to change its media state because the window lost focus.
        prepareBackgroundPlayback();
        super.onUserLeaveHint();
    }

    @Override
    protected void onPause() {
        // Also covers screen-off, calls and other non-user transitions.
        prepareBackgroundPlayback();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepPlaying = false;
        webView.setBackgroundPlayback(false);
        webView.evaluateJavascript(BG_OFF_JS, null);
        webView.onResume();
        stopPlaybackService(); // back on screen: no notification needed
    }

    @Override
    protected void onStop() {
        super.onStop();
        // A bridge event may have arrived between onPause() and onStop().
        // Keep this fallback, but the normal service start happens above.
        prepareBackgroundPlayback();
        // Pausing WebView here while a video is active is what cuts audio.
        if (!keepPlaying) {
            webView.onPause();
            trimCacheIfNeeded();
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            stopPlaybackService();
        }
        sWebView = null;
        resetPagePlaybackState();
        webView.destroy();
        super.onDestroy();
    }

    // --- Cache: keep it, but never let it grow past MAX_CACHE_BYTES ------

    private void trimCacheIfNeeded() {
        try {
            if (dirSize(getCacheDir()) > MAX_CACHE_BYTES) {
                webView.clearCache(true);
            }
        } catch (Exception e) {
            // Best-effort only.
        }
    }

    // Walks the cache folder, stopping early once it is clearly over the limit.
    private long dirSize(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            total += dirSize(child);
            if (total > MAX_CACHE_BYTES) break;
        }
        return total;
    }
}
