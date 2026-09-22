package com.example.ytlite;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.webkit.WebView;

/** Keeps Chromium's native visibility in sync with background playback intent.
 * JavaScript visibility overrides alone cannot stop a native WebView suspension.
 */
public class PlaybackWebView extends WebView {
    private boolean backgroundPlayback;

    public PlaybackWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setBackgroundPlayback(boolean enabled) {
        backgroundPlayback = enabled;
        super.onWindowVisibilityChanged(enabled ? View.VISIBLE : getWindowVisibility());
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        // Some devices deliver this before Activity.onPause(). Capture intent
        // before Chromium receives the hidden-window event and pauses media.
        if (visibility != View.VISIBLE && getContext() instanceof MainActivity) {
            ((MainActivity) getContext()).prepareBackgroundPlayback();
        }
        super.onWindowVisibilityChanged(backgroundPlayback ? View.VISIBLE : visibility);
    }
}
