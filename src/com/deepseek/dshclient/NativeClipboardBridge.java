package com.deepseek.dshclient;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

public final class NativeClipboardBridge {
    public static final String JS_NAME = "DshNativeClipboard";

    private static final String CLIPBOARD_SHIM_JS =
            "(function(){"
            + "if (window.__dshClipboardShimInstalled || !window.DshNativeClipboard) return;"
            + "window.__dshClipboardShimInstalled = true;"
            + "var nativeClip = window.DshNativeClipboard;"
            + "var orig = navigator.clipboard;"
            + "var shim = {"
            + "  writeText: function(text) {"
            + "    var str = String(text == null ? '' : text);"
            + "    if (orig && typeof orig.writeText === 'function') {"
            + "      return orig.writeText(str).catch(function() {"
            + "        nativeClip.writeText(str);"
            + "      });"
            + "    }"
            + "    nativeClip.writeText(str);"
            + "    return Promise.resolve();"
            + "  },"
            + "  readText: function() {"
            + "    if (orig && typeof orig.readText === 'function') {"
            + "      return orig.readText().catch(function() {"
            + "        return nativeClip.readText();"
            + "      });"
            + "    }"
            + "    return Promise.resolve(nativeClip.readText());"
            + "  }"
            + "};"
            + "try {"
            + "  Object.defineProperty(navigator, 'clipboard', { value: shim, configurable: true });"
            + "} catch (e) {"
            + "  if (orig) {"
            + "    try { orig.writeText = shim.writeText; orig.readText = shim.readText; } catch (_) {}"
            + "  }"
            + "}"
            + "})();";

    private final Context appContext;
    private final AppConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String currentUrl = "";

    public NativeClipboardBridge(Context context, AppConfig config) {
        this.appContext = context.getApplicationContext();
        this.config = config;
    }

    public void setCurrentUrl(String url) {
        this.currentUrl = url != null ? url : "";
    }

    public void installShim(WebView webView, String url) {
        setCurrentUrl(url);
        if (config.isLocalDshUrl(url)) {
            webView.evaluateJavascript(CLIPBOARD_SHIM_JS, null);
        }
    }

    @JavascriptInterface
    public boolean writeText(final String text) {
        if (!config.isLocalDshUrl(currentUrl)) {
            return false;
        }
        final String safeText = text != null ? text : "";
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                ClipboardManager cm = (ClipboardManager)
                        appContext.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("DSH", safeText));
                }
            }
        });
        return true;
    }

    @JavascriptInterface
    public String readText() {
        if (!config.isLocalDshUrl(currentUrl)) {
            return "";
        }
        ClipboardManager cm = (ClipboardManager)
                appContext.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            return "";
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return "";
        }
        CharSequence seq = clip.getItemAt(0).coerceToText(appContext);
        return seq != null ? seq.toString() : "";
    }
}
