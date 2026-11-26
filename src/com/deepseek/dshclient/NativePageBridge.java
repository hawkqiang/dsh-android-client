package com.deepseek.dshclient;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/**
 * Reports the page's own background color to the native chrome.
 *
 * <p>DSH lets the user pick 浅色 / 深色 / 跟随系统 independently of the system theme, so the
 * app's own top bar and the strip behind the status bar must follow the page, not the system.
 * A MutationObserver (no polling, no timers) fires whenever DSH flips its theme attribute.</p>
 */
public final class NativePageBridge {
    public static final String JS_NAME = "DshNativePage";

    /** Injected once per document; reports only on actual color changes. */
    private static final String OBSERVER_JS =
            "(function(){"
            + "if (window.__dshPageColorInstalled || !window.DshNativePage) return;"
            + "window.__dshPageColorInstalled = true;"
            + "var bridge = window.DshNativePage;"
            + "var last = '';"
            + "var TRANSPARENT = /^rgba?\\(0, 0, 0, 0\\)$|^transparent$/;"
            + "function pick(el) {"
            + "  if (!el) return '';"
            + "  var c = getComputedStyle(el).backgroundColor;"
            + "  return (!c || TRANSPARENT.test(c)) ? '' : c;"
            + "}"
            + "function report() {"
            + "  var c = pick(document.body) || pick(document.documentElement);"
            + "  if (!c || c === last) return;"
            + "  last = c;"
            + "  try { bridge.onPageColor(c); } catch (e) {}"
            + "}"
            + "report();"
            + "try {"
            + "  new MutationObserver(report).observe(document.documentElement, {"
            + "    attributes: true,"
            + "    attributeFilter: ['class','style','data-theme','data-ds-dark-theme','data-color-scheme'],"
            + "    subtree: false"
            + "  });"
            + "  new MutationObserver(report).observe(document.body, {"
            + "    attributes: true,"
            + "    attributeFilter: ['class','style','data-theme','data-ds-dark-theme','data-color-scheme'],"
            + "    subtree: false"
            + "  });"
            + "} catch (e) {}"
            + "try {"
            + "  window.matchMedia('(prefers-color-scheme: dark)')"
            + "    .addEventListener('change', report);"
            + "} catch (e) {}"
            + "})();";

    public interface ColorListener {
        void onPageColor(int color);
    }

    private final AppConfig config;
    private final ColorListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String currentUrl = "";

    public NativePageBridge(AppConfig config, ColorListener listener) {
        this.config = config;
        this.listener = listener;
    }

    public void setCurrentUrl(String url) {
        this.currentUrl = url != null ? url : "";
    }

    /** Install the observer; safe to call repeatedly (the page guards itself). */
    public void installObserver(WebView webView, String url) {
        setCurrentUrl(url);
        if (config.isLocalDshUrl(url)) {
            webView.evaluateJavascript(OBSERVER_JS, null);
        }
    }

    @JavascriptInterface
    public void onPageColor(final String cssColor) {
        if (!config.isLocalDshUrl(currentUrl)) {
            return;
        }
        final int color = parseCssColor(cssColor);
        if (color == 0) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                listener.onPageColor(color);
            }
        });
    }

    /** Parse "rgb(r, g, b)", "rgba(r, g, b, a)" or "#rrggbb"; 0 means unknown/transparent. */
    static int parseCssColor(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        String text = value.trim().toLowerCase();
        try {
            if (text.startsWith("#")) {
                String hex = text.substring(1);
                if (hex.length() == 3) {
                    hex = "" + hex.charAt(0) + hex.charAt(0)
                            + hex.charAt(1) + hex.charAt(1)
                            + hex.charAt(2) + hex.charAt(2);
                }
                if (hex.length() != 6) {
                    return 0;
                }
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
            if (text.startsWith("rgb")) {
                int open = text.indexOf('(');
                int close = text.indexOf(')');
                if (open < 0 || close <= open) {
                    return 0;
                }
                String[] parts = text.substring(open + 1, close).split(",");
                if (parts.length < 3) {
                    return 0;
                }
                int r = (int) Float.parseFloat(parts[0].trim());
                int g = (int) Float.parseFloat(parts[1].trim());
                int b = (int) Float.parseFloat(parts[2].trim());
                float alpha = 1f;
                if (parts.length >= 4) {
                    alpha = Float.parseFloat(parts[3].trim());
                }
                if (alpha <= 0f) {
                    return 0;
                }
                return 0xFF000000
                        | (clamp255(r) << 16)
                        | (clamp255(g) << 8)
                        | clamp255(b);
            }
        } catch (NumberFormatException ignored) {
            return 0;
        }
        return 0;
    }

    private static int clamp255(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }
}