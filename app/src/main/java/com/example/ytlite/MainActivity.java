package com.example.ytlite;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import java.io.File;

/**
 * Single-Activity, single-WebView "app" targeting very low-RAM / low-storage
 * devices (e.g. Redmi Go, Android 8.1 Go edition).
 *
 * Design choices that keep this small & light:
 *  - Plain java.app.Activity, no AndroidX/AppCompat, no third-party libs.
 *    That alone is the difference between a ~4-6 MB "hello world" APK and
 *    a ~700 KB - 1.5 MB one once R8 shrinking runs.
 *  - Single Activity, no fragments / navigation library.
 *  - Cache is capped and wiped whenever the app is backgrounded or closed.
 */
public class MainActivity extends Activity {

    // Swap this for an Invidious instance (e.g. "https://yewtu.be") for an
    // ad-free, even lighter front-end. m.youtube.com works out of the box
    // with Google login / watch history / recommendations; Invidious
    // instances vary in uptime and don't support Google login.
    private static final String HOME_URL = "https://m.youtube.com";

    // Only these hosts are allowed to load *inside* the WebView. Anything
    // else is handed off to the system browser via ACTION_VIEW.
    private static final String[] ALLOWED_HOSTS = {
            "m.youtube.com",
            "www.youtube.com",
            "youtube.com",
            "youtu.be",
            "yewtu.be",
            "accounts.google.com",
            "consent.google.com"
    };

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

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
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

        // Normal HTTP cache validation; no large pre-reserved app-cache.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // Small UA tweak so youtube.com is more likely to keep serving the
        // lightweight mobile layout rather than the desktop one.
        settings.setUserAgentString(settings.getUserAgentString() + " YTLite/1.0 (Android Go)");

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

                // intent://, market://, vnd.youtube://, etc. — the mobile
                // site sometimes tries to deep-link into the real YouTube
                // app. That app isn't installed on this device, so just
                // swallow the request and stay on the web page.
                openExternally(url);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
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

    private void openExternally(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            // No app / browser can handle it — nothing we can do, ignore.
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

    // Hardware back key: exit fullscreen first if a video is playing
    // fullscreen, else walk back through WebView history, else finish.
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

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    // --- Aggressive cache management -----------------------------------
    // Cleared whenever the app is backgrounded or destroyed, so YouTube's
    // video/image cache never accumulates on the device's limited internal
    // storage. Cookies / localStorage are intentionally left untouched so
    // the user isn't logged out every time the app closes — only
    // clearCache(true) + the raw cache directory are wiped.

    @Override
    protected void onStop() {
        super.onStop();
        clearWebViewCache();
    }

    @Override
    protected void onDestroy() {
        clearWebViewCache();
        webView.destroy();
        super.onDestroy();
    }

    private void clearWebViewCache() {
        webView.clearCache(true);
        try {
            File cacheDir = getApplicationContext().getCacheDir();
            deleteRecursive(cacheDir);
        } catch (Exception e) {
            // Non-fatal — cache cleanup is best-effort.
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        } else {
            file.delete();
        }
    }
}
