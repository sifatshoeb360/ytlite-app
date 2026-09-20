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
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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

    // Injected into every page. Compact on purpose (runs on a weak CPU).
    //  - Makes the page believe it is always visible, so YouTube does not
    //    pause playback when the screen turns off.
    //  - Hides ad containers with CSS.
    //  - During a video ad: clicks Skip, mutes, speeds up and jumps to end.
    // YouTube changes its markup from time to time, so the selectors below
    // may need updating occasionally.
    private static final String PAGE_JS =
            "(function(){if(window.__ytl)return;window.__ytl=1;"
            + "try{['hidden','webkitHidden'].forEach(function(k){"
            + "Object.defineProperty(document,k,{configurable:true,get:function(){return false}})});"
            + "['visibilityState','webkitVisibilityState'].forEach(function(k){"
            + "Object.defineProperty(document,k,{configurable:true,get:function(){return 'visible'}})})}catch(e){}"
            + "var stop=function(e){e.stopImmediatePropagation()};"
            + "window.addEventListener('visibilitychange',stop,true);"
            + "document.addEventListener('visibilitychange',stop,true);"
            + "window.addEventListener('webkitvisibilitychange',stop,true);"
            + "var CSS='ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,"
            + "ytm-companion-ad-renderer,ytm-ad-slot-renderer,ad-slot-renderer,"
            + "ytm-brand-video-singleton-renderer,#player-ads,.ytp-ad-overlay-container,"
            + ".ytp-ad-image-overlay{display:none!important}';"
            + "var fast=0,pm=false;"
            + "function tick(){"
            + "if(!document.getElementById('ytl-css')){var r=document.head||document.documentElement;"
            + "if(r){var s=document.createElement('style');s.id='ytl-css';s.textContent=CSS;r.appendChild(s)}}"
            + "var p=document.querySelector('.html5-video-player'),v=document.querySelector('video');"
            + "if(!p||!v)return;"
            + "if(p.classList.contains('ad-showing')){"
            + "var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');"
            + "if(b)b.click();"
            + "if(!fast){pm=v.muted;fast=1}"
            + "v.muted=true;v.playbackRate=16;"
            + "if(isFinite(v.duration)&&v.duration>0)v.currentTime=v.duration;"
            + "}else if(fast){v.playbackRate=1;v.muted=pm;fast=0}}"
            + "setInterval(tick,400)})();";

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;

    // State kept while a <video> element is in native fullscreen mode
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int originalOrientation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);

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
        // Hardware layer -> smooth <video> playback / scrolling on weak GPUs
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

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

    @Override
    protected void onPause() {
        super.onPause();
        // Screen turning off / leaving the app while something is playing:
        // start the foreground service *now*, while the app still counts as
        // being in the foreground (required on newer Android versions).
        if (!isFinishing() && isAudioPlaying()) {
            startPlaybackService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        stopPlaybackService(); // back on screen: no notification needed
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Only pause the WebView and trim the cache when nothing is playing.
        // Pausing it while audio plays is what would cut the sound.
        if (!isAudioPlaying()) {
            webView.onPause();
            trimCacheIfNeeded();
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            stopPlaybackService();
        }
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
