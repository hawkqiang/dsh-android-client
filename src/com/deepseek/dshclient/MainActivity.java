package com.deepseek.dshclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements TermuxController.ResultListener {
    private static final int REQ_TERMUX_PERMISSION = 1001;
    private static final int REQ_FILE_CHOOSER = 2001;

    private AppConfig config;
    private NativeClipboardBridge clipboardBridge;
    private NativePageBridge pageBridge;
    private WebViewDownloadHelper downloadHelper;

    private LinearLayout shellLayout;
    private LinearLayout topBar;
    private View topBarDivider;
    private FrameLayout rootLayout;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout offlinePanel;
    private TextView offlineTitle;
    private TextView offlineDetail;
    private View titleBlock;
    private TextView titleView;
    private TextView subtitleView;
    private View statusDot;
    private ImageView menuButton;

    private ValueCallback<Uri[]> filePathCallback;
    private String pendingTermuxAction;
    private boolean pendingTermuxInteractive = true;
    private boolean pendingExitAfterStop = false;
    private long lastBackPressMs = 0L;
    private boolean webViewLoadedOnce = false;
    // DSH web requires a per-process launch token on first visit; cookies persist afterwards.
    private boolean authUrlRequestPending = false;
    private boolean authRetryAttempted = false;
    private boolean lastMainFrameUnauthorized = false;
    private boolean termuxPermissionDenied = false;
    private int authAttempts = 0;
    private boolean nightModeActive = false;
    private boolean fullscreenActive = false;
    private int pageBackgroundColor = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        config = new AppConfig(this);
        clipboardBridge = new NativeClipboardBridge(this, config);
        downloadHelper = new WebViewDownloadHelper(this, config);
        pageBridge = new NativePageBridge(config, new NativePageBridge.ColorListener() {
            @Override
            public void onPageColor(int color) {
                applyPageBackground(color);
            }
        });
        nightModeActive = isNightMode();

        // Own the whole window on every API level: the shell clears the status bar,
        // navigation bar, cutout and IME itself instead of relying on the decor padding
        // (Android 15+ forces edge-to-edge anyway, so this keeps all versions identical).
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        buildUi();
        setupWebView();
        webView.setBackgroundColor(nightModeActive ? 0xFF0F172A : 0xFFFFFFFF);
        applyFullscreenState(config.isFullscreen(), false);
        TermuxController.setListener(this);

        updateActionBarSubtitle("正在检测 " + config.getHost() + ":" + config.getPort());
        probeAndLoad(true);
    }

    private void buildUi() {
        shellLayout = new LinearLayout(this);
        shellLayout.setOrientation(LinearLayout.VERTICAL);
        shellLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        shellLayout.setFitsSystemWindows(false);

        // Custom, insets-aware top bar. Android 15/16 forces edge-to-edge for targetSdk 35,
        // so the framework ActionBar would sit under the status bar; owning the bar keeps the
        // title, the status line and the menu button clear of the system UI on every device.
        buildTopBar();
        shellLayout.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        topBarDivider = new View(this);
        topBarDivider.setBackgroundColor(isNightMode() ? 0xFF1E293B : 0xFFE2E8F0);
        shellLayout.addView(topBarDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        rootLayout = new FrameLayout(this);
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        rootLayout.setFitsSystemWindows(false);

        // Handle Android 15/16 edge-to-edge, display cutouts, tablet bars, external display & IME.
        shellLayout.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            @SuppressWarnings("deprecation")
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left;
                int right;
                int statusTop;
                int cutoutTop;
                int bottom;

                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    android.graphics.Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                    android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    left = Math.max(bars.left, cutout.left);
                    right = Math.max(bars.right, cutout.right);
                    statusTop = bars.top;
                    cutoutTop = cutout.top;
                    bottom = Math.max(Math.max(bars.bottom, cutout.bottom), ime.bottom);
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                    statusTop = insets.getSystemWindowInsetTop();
                    cutoutTop = statusTop;
                    bottom = insets.getSystemWindowInsetBottom();
                }

                if (topBar.getVisibility() == View.VISIBLE) {
                    // The bar itself extends behind the status bar so its background stays flush.
                    topBar.setPadding(left + dp(12), statusTop + dp(6), right + dp(4), dp(6));
                    rootLayout.setPadding(left, 0, right, bottom);
                } else {
                    // Immersive app chrome: our bar is gone but the system status bar (clock)
                    // stays visible, so the page still starts below it instead of being covered.
                    topBar.setPadding(left + dp(12), statusTop + dp(6), right + dp(4), dp(6));
                    rootLayout.setPadding(left, Math.max(statusTop, cutoutTop), right, bottom);
                }
                return insets;
            }
        });

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.addView(webView);

        // Thin horizontal loading progress bar at top of WebView.
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams pbParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        pbParams.gravity = Gravity.TOP;
        rootLayout.addView(progressBar, pbParams);

        // Native offline / backend control overlay (shown when localhost is unreachable).
        buildOfflinePanel();
        rootLayout.addView(offlinePanel);

        shellLayout.addView(rootLayout, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(shellLayout);
    }

    /**
     * Build the app's own slim top bar: a status dot, the page title plus a one-line
     * connection status, and a single menu button. Nothing floats over the page, so it
     * can never overlap the DSH UI's own top-right controls.
     */
    private void buildTopBar() {
        boolean night = isNightMode();
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(night ? 0xFF0F172A : 0xFFFFFFFF);

        statusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.leftMargin = dp(2);
        dotLp.rightMargin = dp(10);
        topBar.addView(statusDot, dotLp);
        setStatusDotColor(0xFFF59E0B);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(this);
        titleView.setText(R.string.app_title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(night ? 0xFFF8FAFC : 0xFF0F172A);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);

        subtitleView = new TextView(this);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subtitleView.setTextColor(night ? 0xFF94A3B8 : 0xFF64748B);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(subtitleView);

        topBar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        menuButton = new ImageView(this);
        menuButton.setImageResource(R.drawable.ic_menu_bars);
        menuButton.setScaleType(ImageView.ScaleType.CENTER);
        menuButton.setContentDescription(getString(R.string.menu_open));
        menuButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        TypedValue ripple = new TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                && ripple.resourceId != 0) {
            menuButton.setBackgroundResource(ripple.resourceId);
        }
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMainMenu(v);
            }
        });
        topBar.addView(menuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }

    private void setStatusDotColor(int color) {
        if (statusDot == null) {
            return;
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        statusDot.setBackground(dot);
    }

    private void buildOfflinePanel() {
        offlinePanel = new LinearLayout(this);
        offlinePanel.setOrientation(LinearLayout.VERTICAL);
        offlinePanel.setGravity(Gravity.CENTER);
        offlinePanel.setPadding(dp(28), dp(24), dp(28), dp(24));
        offlinePanel.setBackgroundColor(isNightMode() ? 0xFF0F172A : 0xFFF8FAFC);
        offlinePanel.setVisibility(View.GONE);
        offlinePanel.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(isNightMode() ? 0xFF1E293B : 0xFFFFFFFF);
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(1), isNightMode() ? 0xFF334155 : 0xFFE2E8F0);
        card.setBackground(cardBg);

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        // Keep card readable on wide tablets and external monitors.
        int maxWidthPx = dp(480);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        if (screenWidth > maxWidthPx + dp(64)) {
            cardLp.width = maxWidthPx;
        }

        offlineTitle = new TextView(this);
        offlineTitle.setText("未连接到 DeepSeek Harness 后端");
        offlineTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        offlineTitle.setTypeface(Typeface.DEFAULT_BOLD);
        offlineTitle.setTextColor(isNightMode() ? 0xFFF8FAFC : 0xFF0F172A);
        card.addView(offlineTitle);

        offlineDetail = new TextView(this);
        offlineDetail.setText("目标地址: " + config.getBaseUrl()
                + "\n\n点击下方“启动后端”将通过 Termux RUN_COMMAND 在后台拉起现有 DSH 服务。");
        offlineDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        offlineDetail.setTextColor(isNightMode() ? 0xFFCBD5E1 : 0xFF475569);
        offlineDetail.setPadding(0, dp(10), 0, dp(18));
        card.addView(offlineDetail);

        Button btnStart = createActionButton("启动后端", true);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxCommand(TermuxController.CMD_START, true);
            }
        });
        card.addView(btnStart);

        LinearLayout row1 = createButtonRow();
        Button btnRetry = createActionButton("刷新重试", false);
        btnRetry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeAndLoad(true);
            }
        });
        Button btnStatus = createActionButton("检测状态", false);
        btnStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxCommand(TermuxController.CMD_STATUS, true);
            }
        });
        addEqualButtons(row1, btnRetry, btnStatus);
        card.addView(row1);

        LinearLayout row2 = createButtonRow();
        Button btnLogs = createActionButton("查看日志", false);
        btnLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxCommand(TermuxController.CMD_LOGS, true);
            }
        });
        Button btnSettings = createActionButton("连接配置", false);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
        addEqualButtons(row2, btnLogs, btnSettings);
        card.addView(row2);

        offlinePanel.addView(card, cardLp);
    }

    private LinearLayout createButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);
        return row;
    }

    private void addEqualButtons(LinearLayout row, Button left, Button right) {
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpLeft.rightMargin = dp(5);
        LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpRight.leftMargin = dp(5);
        row.addView(left, lpLeft);
        row.addView(right, lpRight);
    }

    private Button createActionButton(String label, boolean primary) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        if (primary) {
            bg.setColor(0xFF2563EB);
            btn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(isNightMode() ? 0xFF334155 : 0xFFE2E8F0);
            btn.setTextColor(isNightMode() ? 0xFFF8FAFC : 0xFF0F172A);
        }
        btn.setBackground(bg);
        btn.setPadding(dp(14), dp(10), dp(14), dp(10));
        return btn;
    }

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // DSH owns its colors via 通用 → 外观 (浅色 / 深色 / 跟随系统, applied through
        // CSS color-scheme + prefers-color-scheme). Any WebView-side darkening would
        // stack on top of those variables and render the UI with wrong colors, so all
        // automatic transformations stay off and the page is rendered as authored.
        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (Build.VERSION.SDK_INT >= 29) {
            disableLegacyForceDark(settings);
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(clipboardBridge, NativeClipboardBridge.JS_NAME);
        webView.addJavascriptInterface(downloadHelper, WebViewDownloadHelper.JS_NAME);
        webView.addJavascriptInterface(pageBridge, NativePageBridge.JS_NAME);

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
            ) {
                downloadHelper.handleDownload(
                        webView, url, userAgent, contentDisposition, mimeType);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) {
                    return false;
                }
                String url = uri.toString();
                if (config.isLocalDshUrl(url)) {
                    return false;
                }
                openExternalUri(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                clipboardBridge.setCurrentUrl(url);
                downloadHelper.setCurrentUrl(url);
                pageBridge.setCurrentUrl(url);
                lastMainFrameUnauthorized = false;
                authRetryAttempted = false;
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(10);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                clipboardBridge.installShim(view, url);
                downloadHelper.setCurrentUrl(url);
                pageBridge.installObserver(view, url);
                if (config.isLocalDshUrl(url) && !lastMainFrameUnauthorized) {
                    webViewLoadedOnce = true;
                    authAttempts = 0;
                    hideOfflinePanel();
                    updateActionBarSubtitle("已连接 · " + config.getHost() + ":" + config.getPort());
                }
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedHttpError(
                    WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request == null || !request.isForMainFrame() || errorResponse == null) {
                    return;
                }
                if (errorResponse.getStatusCode() == 401) {
                    lastMainFrameUnauthorized = true;
                    updateActionBarSubtitle("需要认证 · 正在获取令牌");
                    if (!authRetryAttempted) {
                        authRetryAttempted = true;
                        if (!requestAuthUrlFromTermux()) {
                            showOfflinePanel(
                                    "DSH 需要认证",
                                    "服务端拒绝了未携带启动令牌的请求。\n\n"
                                            + "自动获取令牌需要通过 Termux 执行控制脚本：\n"
                                            + "1. 授予本应用的 Termux 运行命令权限；\n"
                                            + "2. 或在 Termux 中运行 dsh web，复制其输出的链接，"
                                            + "然后使用菜单「粘贴认证链接」。"
                            );
                            updateActionBarSubtitle("需要认证");
                        }
                    }
                }
            }

            @Override
            public void onReceivedError(
                    WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    CharSequence desc = error != null ? error.getDescription() : "连接失败";
                    showOfflinePanel(
                            "无法连接到本地 DSH 服务",
                            "地址: " + config.getBaseUrl()
                                    + "\n原因: " + desc
                                    + "\n\n可点击“启动后端”通过 Termux 启动服务，或点击“查看日志”排查。"
                    );
                    updateActionBarSubtitle("未连接 · " + config.getHost() + ":" + config.getPort());
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Toast.makeText(MainActivity.this,
                        "WebView 渲染进程已重建，正在恢复页面...", Toast.LENGTH_SHORT).show();
                recreateWebView();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (titleView != null && !TextUtils.isEmpty(title) && !title.startsWith("http")) {
                    titleView.setText(title);
                    titleView.setEllipsize(TextUtils.TruncateAt.END);
                }
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                cancelPendingFileChooser();
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");

                if (fileChooserParams != null) {
                    if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    String[] acceptTypes = fileChooserParams.getAcceptTypes();
                    String[] mimeTypes = resolveMimeTypes(acceptTypes);
                    if (mimeTypes.length == 1) {
                        intent.setType(mimeTypes[0]);
                    } else if (mimeTypes.length > 1) {
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                    }
                }

                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "选择文件"),
                            REQ_FILE_CHOOSER
                    );
                    return true;
                } catch (Exception e) {
                    cancelPendingFileChooser();
                    Toast.makeText(MainActivity.this,
                            "无法打开系统文件选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(
                    WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.HitTestResult hit = view.getHitTestResult();
                String extra = hit != null ? hit.getExtra() : null;
                if (!TextUtils.isEmpty(extra)) {
                    if (config.isLocalDshUrl(extra)) {
                        view.loadUrl(extra);
                    } else {
                        openExternalUri(Uri.parse(extra));
                    }
                    return false;
                }
                // Handle JS window.open() without leaking a new heavy WebView.
                WebView transportView = new WebView(MainActivity.this);
                transportView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView v, WebResourceRequest req) {
                        Uri u = req.getUrl();
                        if (u != null) {
                            String url = u.toString();
                            if (config.isLocalDshUrl(url)) {
                                webView.loadUrl(url);
                            } else {
                                openExternalUri(u);
                            }
                        }
                        v.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(transportView);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                Uri origin = request.getOrigin();
                if (origin != null && config.isLocalDshUrl(origin.toString())) {
                    request.grant(request.getResources());
                } else {
                    request.deny();
                }
            }

            @Override
            public boolean onJsAlert(
                    WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.app_title)
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(
                    WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.app_title)
                        .setMessage(message)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(
                    WebView view,
                    String url,
                    String message,
                    String defaultValue,
                    final JsPromptResult result
            ) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue != null ? defaultValue : "");
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.app_title)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static void disableLegacyForceDark(WebSettings settings) {
        try {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        } catch (Throwable ignored) {
            // Older WebView providers may not implement force dark at all.
        }
    }

    private void recreateWebView() {
        String lastUrl = config.getBaseUrl();
        if (webView != null) {
            if (!TextUtils.isEmpty(webView.getUrl()) && config.isLocalDshUrl(webView.getUrl())) {
                lastUrl = webView.getUrl();
            }
            rootLayout.removeView(webView);
            webView.destroy();
        }
        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.addView(webView, 0);
        setupWebView();
        webView.loadUrl(lastUrl);
    }

    /**
     * One-shot non-polling TCP probe to avoid ugly WebView connection refused page on cold start.
     */
    private void probeAndLoad(final boolean loadBaseIfBlank) {
        authAttempts = 0;
        final String host = config.getHost();
        final int port = config.getPort();
        final String targetUrl = config.getBaseUrl();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean reachable = isTcpPortReachable(host, port, 1200);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        if (reachable) {
                            hideOfflinePanel();
                            updateActionBarSubtitle("已连接 · " + host + ":" + port);
                            String current = webView.getUrl();
                            if (!loadBaseIfBlank && !TextUtils.isEmpty(current)
                                    && config.isLocalDshUrl(current)) {
                                webView.reload();
                            } else {
                                loadEntryUrl(targetUrl);
                            }
                        } else {
                            updateActionBarSubtitle("后端未运行 · " + host + ":" + port);
                            showOfflinePanel(
                                    "DSH 后端尚未启动",
                                    "目标地址: " + targetUrl
                                            + "\n\n点击“启动后端”通过 Termux 在后台启动现有 DSH 服务。"
                                            + "\n关闭本客户端不会影响 Termux 中运行中的 DSH 任务。"
                            );
                        }
                    }
                });
            }
        }, "dsh-tcp-probe").start();
    }

    /**
     * Load the DSH entry URL, exchanging the process launch token for a browser
     * cookie when the app has no session cookie yet. No polling: a single
     * non-interactive Termux call resolves an already-running backend.
     */
    private void loadEntryUrl(String targetUrl) {
        String cookie = CookieManager.getInstance().getCookie(targetUrl);
        if (!TextUtils.isEmpty(cookie)) {
            webView.loadUrl(targetUrl);
            return;
        }
        // The Termux round-trip resolves the token; its result loads the page.
        if (requestAuthUrlFromTermux()) {
            return;
        }
        if (TermuxController.isTermuxInstalled(this)
                && !TermuxController.hasRunCommandPermission(this)
                && !termuxPermissionDenied) {
            requestTermuxPermission(TermuxController.CMD_URL, false);
            updateActionBarSubtitle("等待 Termux 权限 · " + config.getHost() + ":" + config.getPort());
            showOfflinePanel(
                    "首次使用需要 Termux 运行命令权限",
                    "DSH 服务只接受携带启动令牌的访问。\n\n授予 Termux 运行命令权限后，"
                            + "客户端会自动取得令牌并加载页面。\n\n也可以在 Termux 中运行 dsh web，"
                            + "复制其输出的链接，再用菜单「粘贴认证链接」直接打开。"
            );
            return;
        }
        webView.loadUrl(targetUrl);
    }

    /**
     * Ask the Termux control script for the tokenized URL printed by `dsh web`.
     * Returns false when Termux is unavailable or the permission is missing, so
     * the caller can fall back to the plain base URL.
     */
    private boolean requestAuthUrlFromTermux() {
        if (authUrlRequestPending) {
            return true;
        }
        if (authAttempts >= 3) {
            return false;
        }
        if (!TermuxController.isTermuxInstalled(this)
                || !TermuxController.hasRunCommandPermission(this)) {
            return false;
        }
        try {
            authUrlRequestPending = true;
            authAttempts++;
            updateActionBarSubtitle("正在获取 DSH 认证链接...");
            TermuxController.execute(this, config, TermuxController.CMD_URL, false);
            return true;
        } catch (Exception e) {
            authUrlRequestPending = false;
            return false;
        }
    }

    private void showPasteAuthUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint("http://127.0.0.1:3080/?token=...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        String clipboard = readClipboardText();
        if (!TextUtils.isEmpty(clipboard) && clipboard.contains("token=")) {
            input.setText(clipboard.trim());
        }

        new AlertDialog.Builder(this)
                .setTitle("粘贴认证链接")
                .setMessage("在 Termux 中运行 dsh web 后，将其输出的 http://...?token=... 链接粘贴到下方。"
                        + "链接只需使用一次，之后会保存 Cookie。")
                .setView(input)
                .setPositiveButton("打开", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String url = input.getText().toString().trim();
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            Toast.makeText(MainActivity.this, "请输入完整的 http(s) 链接",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        authAttempts = 0;
                        hideOfflinePanel();
                        webView.loadUrl(url);
                    }
                })
                .setNeutralButton("从剪贴板填入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String text = readClipboardText();
                        if (TextUtils.isEmpty(text)) {
                            Toast.makeText(MainActivity.this, "剪贴板为空", Toast.LENGTH_SHORT).show();
                        } else {
                            input.setText(text.trim());
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestTermuxPermission(String action, boolean interactive) {
        pendingTermuxAction = action;
        pendingTermuxInteractive = interactive;
        try {
            requestPermissions(
                    new String[]{TermuxController.PERMISSION_RUN_COMMAND},
                    REQ_TERMUX_PERMISSION
            );
        } catch (Exception e) {
            pendingTermuxAction = null;
            showTermuxHelpDialog("无法请求 Termux 权限",
                    "请在系统设置中为本应用授予 Termux 运行命令权限。\n" + e.getMessage());
        }
    }

    private String readClipboardText() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            return "";
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip.getItemCount() == 0) {
            return "";
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text != null ? text.toString() : "";
    }

    private static boolean isTcpPortReachable(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void showOfflinePanel(String title, String detail) {
        offlineTitle.setText(title);
        offlineDetail.setText(detail);
        offlinePanel.setVisibility(View.VISIBLE);
    }

    private void hideOfflinePanel() {
        offlinePanel.setVisibility(View.GONE);
    }

    private void updateActionBarSubtitle(String subtitle) {
        if (subtitleView == null) {
            return;
        }
        subtitleView.setText(subtitle);

        int dotColor;
        if (subtitle.startsWith("已连接") || subtitle.startsWith("运行中")) {
            dotColor = 0xFF22C55E;
        } else if (subtitle.startsWith("后端未运行") || subtitle.startsWith("已停止")
                || subtitle.contains("失败") || subtitle.contains("异常")) {
            dotColor = 0xFFEF4444;
        } else {
            dotColor = 0xFFF59E0B;
        }
        setStatusDotColor(dotColor);
    }

    /**
     * Toggle immersive fullscreen: the top bar and the system bars hide together so the
     * page really is edge-to-edge. The back key (or the menu item) restores the bar.
     */
    private void applyFullscreenState(boolean fullscreen, boolean persist) {
        if (persist) {
            config.setFullscreen(fullscreen);
        }
        fullscreenActive = fullscreen;

        if (topBar != null) {
            topBar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (topBarDivider != null) {
            topBarDivider.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        syncSystemBars();
        if (shellLayout != null) {
            shellLayout.requestApplyInsets();
        }
        if (persist) {
            Toast.makeText(this,
                    fullscreen ? "已隐藏顶栏（状态栏保留），按返回键恢复" : "已恢复顶栏",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Keep status/navigation bar icon contrast and visibility in step with the app state. */
    @SuppressWarnings("deprecation")
    private void syncSystemBars() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        // The status bar stays visible in every mode (the clock row must never disappear);
        // only its icon contrast follows the chrome color the page actually paints.
        boolean lightBackground = isLightColor(chromeBackgroundColor());

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }
            int appearanceMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(
                    lightBackground ? appearanceMask : 0, appearanceMask);
            return;
        }

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (lightBackground) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** Background the chrome shares with the page: sampled page color, else the app theme. */
    private int chromeBackgroundColor() {
        if (pageBackgroundColor != 0) {
            return pageBackgroundColor;
        }
        return nightModeActive ? 0xFF0F1115 : 0xFFFFFFFF;
    }

    private static boolean isLightColor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance >= 0.5;
    }

    private void runTermuxCommand(String action, boolean interactive) {
        if (!TermuxController.isTermuxInstalled(this)) {
            showTermuxHelpDialog("未检测到 Termux",
                    "请先安装 Termux 并在其中运行现有的 DeepSeek Harness 环境。");
            return;
        }
        if (!TermuxController.hasRunCommandPermission(this)) {
            requestTermuxPermission(action, interactive);
            return;
        }

        if (TermuxController.CMD_START.equals(action)) {
            updateActionBarSubtitle("正在通过 Termux 启动后端...");
            Toast.makeText(this, "正在启动 DSH 后端...", Toast.LENGTH_SHORT).show();
        } else if (TermuxController.CMD_RESTART.equals(action)) {
            updateActionBarSubtitle("正在重启 DSH 后端...");
            Toast.makeText(this, "正在重启 DSH 后端...", Toast.LENGTH_SHORT).show();
        } else if (TermuxController.CMD_STOP.equals(action)) {
            updateActionBarSubtitle("正在停止 DSH 后端...");
            Toast.makeText(this, "正在停止 DSH 后端...", Toast.LENGTH_SHORT).show();
        }

        try {
            TermuxController.execute(this, config, action, interactive);
        } catch (SecurityException e) {
            showTermuxHelpDialog("Termux 权限不足",
                    "无法调用 Termux RUN_COMMAND。\n\n请检查：\n"
                            + "1. 在系统应用权限中授予本应用 Termux RUN_COMMAND 权限；\n"
                            + "2. 在 ~/.termux/termux.properties 中启用：\n"
                            + "   allow-external-apps = true\n"
                            + "3. 在 Termux 中执行 termux-reload-settings。\n\n详情: " + e.getMessage());
        } catch (Exception e) {
            showTermuxHelpDialog("调用 Termux 失败",
                    "执行命令失败 (" + action + ")：\n" + e.getMessage()
                            + "\n\n如果 Android 限制后台启动 Termux 服务，请先打开一次 Termux 或在系统电池设置中允许 Termux 后台运行。");
        }
    }

    @Override
    public void onCommandResult(TermuxController.CommandResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        // Silent token resolution: load the tokenized URL `dsh web` printed.
        if (TermuxController.CMD_URL.equals(result.action)) {
            authUrlRequestPending = false;
            if (result.hasPluginError()) {
                webView.loadUrl(config.getBaseUrl());
                return;
            }
            String authUrl = result.getField("URL", "");
            hideOfflinePanel();
            if (!TextUtils.isEmpty(authUrl)
                    && (authUrl.startsWith("http://") || authUrl.startsWith("https://"))) {
                webView.loadUrl(authUrl);
            } else {
                webView.loadUrl(config.getBaseUrl());
            }
            return;
        }

        if (result.hasPluginError()) {
            authUrlRequestPending = false;
            updateActionBarSubtitle("Termux 调用异常");
            showTermuxHelpDialog("Termux 执行错误",
                    "命令: " + result.action
                            + "\n错误码: " + result.err
                            + "\n信息: " + result.errmsg
                            + "\n\n请确认控制脚本存在且可执行：\n" + config.getScriptPath()
                            + "\n且 ~/.termux/termux.properties 中 allow-external-apps = true。");
            return;
        }

        String status = result.getStatusValue();
        String msg = result.getField("MESSAGE", "");

        if (TermuxController.CMD_LOGS.equals(result.action)) {
            String logText = !TextUtils.isEmpty(result.stdout) ? result.stdout : result.stderr;
            showLogsDialog(TextUtils.isEmpty(logText) ? "(暂无日志输出)" : logText);
            return;
        }

        if (TermuxController.CMD_STATUS.equals(result.action)) {
            if ("RUNNING".equals(status)) {
                updateActionBarSubtitle("运行中 · PID " + result.getField("PID", "?"));
            } else if ("STARTING".equals(status)) {
                updateActionBarSubtitle("正在启动 · PID " + result.getField("PID", "?"));
            } else {
                updateActionBarSubtitle("后端已停止");
            }
            if (result.interactive) {
                showStatusDialog(result);
            }
            return;
        }

        if (TermuxController.CMD_START.equals(result.action)
                || TermuxController.CMD_RESTART.equals(result.action)) {
            if ("RUNNING".equals(status) || "STARTING".equals(status)) {
                Toast.makeText(this,
                        TextUtils.isEmpty(msg) ? "DSH 后端已就绪" : msg,
                        Toast.LENGTH_SHORT).show();
                updateActionBarSubtitle("已连接 · " + config.getHost() + ":" + config.getPort());
                hideOfflinePanel();
                String authUrl = result.getField("AUTH_URL", "");
                webView.loadUrl(!TextUtils.isEmpty(authUrl) ? authUrl : config.getBaseUrl());
            } else {
                updateActionBarSubtitle("后端启动失败");
                String detail = !TextUtils.isEmpty(result.stdout) ? result.stdout : result.stderr;
                showOfflinePanel("DSH 后端启动失败",
                        TextUtils.isEmpty(detail) ? "退出码: " + result.exitCode : detail);
            }
            return;
        }

        if (TermuxController.CMD_STOP.equals(result.action)) {
            updateActionBarSubtitle("后端已停止");
            Toast.makeText(this,
                    TextUtils.isEmpty(msg) ? "DSH 后端已停止" : msg,
                    Toast.LENGTH_SHORT).show();
            if (pendingExitAfterStop) {
                pendingExitAfterStop = false;
                closeFrontendOnly();
                return;
            }
            showOfflinePanel(
                    "DSH 后端已停止",
                    "目标地址: " + config.getBaseUrl() + "\n\n可随时点击“启动后端”重新拉起服务。"
            );
        }
    }

    private void showStatusDialog(TermuxController.CommandResult result) {
        String status = result.getStatusValue();
        String statusLabel;
        if ("RUNNING".equals(status)) {
            statusLabel = "运行中 (RUNNING)";
        } else if ("STARTING".equals(status)) {
            statusLabel = "启动中 (STARTING)";
        } else if ("STOPPED".equals(status)) {
            statusLabel = "已停止 (STOPPED)";
        } else {
            statusLabel = TextUtils.isEmpty(status) ? "未知 (exit=" + result.exitCode + ")" : status;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("状态: ").append(statusLabel).append('\n');
        sb.append("PID: ").append(result.getField("PID", "none")).append('\n');
        sb.append("地址: ").append(result.getField("URL", config.getBaseUrl())).append('\n');
        sb.append("端口监听: ").append(result.getField("PORT_OPEN", "unknown")).append('\n');
        String uptime = result.getField("UPTIME", "");
        if (!TextUtils.isEmpty(uptime)) {
            sb.append("运行时长: ").append(uptime).append('\n');
        }
        sb.append("日志路径: ").append(result.getField("LOG", "-"));
        String otherPorts = result.getField("OTHER_PORTS", "");
        if (!TextUtils.isEmpty(otherPorts)) {
            sb.append("\n其他端口上的实例: ").append(otherPorts)
                    .append("（可在“连接与脚本配置”中修改端口）");
        }
        if (!TextUtils.isEmpty(result.stderr)) {
            sb.append("\n\nstderr:\n").append(result.stderr.trim());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("DSH 后端状态")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .setNeutralButton("查看日志", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        runTermuxCommand(TermuxController.CMD_LOGS, true);
                    }
                });

        if ("RUNNING".equals(status)) {
            builder.setNegativeButton("重启后端", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    runTermuxCommand(TermuxController.CMD_RESTART, true);
                }
            });
        } else {
            builder.setNegativeButton("启动后端", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    runTermuxCommand(TermuxController.CMD_START, true);
                }
            });
        }
        builder.show();
    }

    private void showLogsDialog(final String logs) {
        ScrollView vScroll = new ScrollView(this);
        HorizontalScrollView hScroll = new HorizontalScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(logs);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        hScroll.addView(tv);
        vScroll.addView(hScroll);

        new AlertDialog.Builder(this)
                .setTitle("DSH 后端日志")
                .setView(vScroll)
                .setPositiveButton("刷新", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        runTermuxCommand(TermuxController.CMD_LOGS, true);
                    }
                })
                .setNeutralButton("复制日志", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        copyTextToClipboard("DSH Logs", logs);
                        Toast.makeText(MainActivity.this, "日志已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showSettingsDialog() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(8));

        final EditText etHost = addSettingField(form,
                "DSH 主机地址 (默认 127.0.0.1；局域网主机请使用 https)",
                config.getHost(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText etPort = addSettingField(form, "DSH 端口 (默认 3080)",
                String.valueOf(config.getPort()), InputType.TYPE_CLASS_NUMBER);
        final EditText etWorkdir = addSettingField(form, "DSH 启动工作目录",
                config.getWorkdir(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText etScript = addSettingField(form, "Termux 控制脚本路径",
                config.getScriptPath(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        scroll.addView(form);

        new AlertDialog.Builder(this)
                .setTitle("连接与 Termux 控制配置")
                .setView(scroll)
                .setPositiveButton("保存并重连", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int parsedPort = AppConfig.DEFAULT_PORT;
                        try {
                            parsedPort = Integer.parseInt(etPort.getText().toString().trim());
                        } catch (NumberFormatException ignored) {
                        }
                        config.save(
                                etHost.getText().toString(),
                                parsedPort,
                                etScript.getText().toString(),
                                etWorkdir.getText().toString()
                        );
                        probeAndLoad(true);
                    }
                })
                .setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        config.resetDefaults();
                        Toast.makeText(MainActivity.this, "已恢复默认配置", Toast.LENGTH_SHORT).show();
                        probeAndLoad(true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText addSettingField(LinearLayout parent, String label, String value, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setPadding(0, dp(8), 0, dp(2));
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(value);
        et.setInputType(inputType);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }

    private void showTermuxHelpDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void confirmStopBackend(final boolean exitAfterStop) {
        String msg = exitAfterStop
                ? "确认停止 Termux 中的 DSH 后端服务并退出客户端？\n正在执行的后端任务将被中断。"
                : "确认停止 Termux 中的 DSH 后端服务？\n如只需关闭界面并保留后台任务，请选择“仅关闭前端”。";
        new AlertDialog.Builder(this)
                .setTitle(exitAfterStop ? "停止后端并退出" : "停止 DSH 后端")
                .setMessage(msg)
                .setPositiveButton("确认停止", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pendingExitAfterStop = exitAfterStop;
                        runTermuxCommand(TermuxController.CMD_STOP, true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void closeFrontendOnly() {
        CookieManager.getInstance().flush();
        finish();
    }

    /**
     * Native popup menu anchored on the top-bar button. It is the single entry point for
     * every action, including the way back out of immersive fullscreen.
     */
    private void showMainMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.main_menu, popup.getMenu());

        MenuItem fullscreenItem = popup.getMenu().findItem(R.id.action_toggle_fullscreen);
        if (fullscreenItem != null) {
            fullscreenItem.setTitle(fullscreenActive
                    ? "恢复顶栏" : "隐藏顶栏（沉浸模式）");
        }
        MenuItem reloadItem = popup.getMenu().findItem(R.id.action_reload);
        if (reloadItem != null) {
            reloadItem.setTitle("刷新页面");
        }

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                return handleMenuAction(item.getItemId());
            }
        });
        popup.show();
    }

    private boolean handleMenuAction(int id) {
        if (id == R.id.action_reload) {
            if (offlinePanel.getVisibility() == View.VISIBLE || !webViewLoadedOnce) {
                probeAndLoad(true);
            } else {
                webView.reload();
            }
            return true;
        } else if (id == R.id.action_backend_status) {
            runTermuxCommand(TermuxController.CMD_STATUS, true);
            return true;
        } else if (id == R.id.action_backend_start) {
            runTermuxCommand(TermuxController.CMD_START, true);
            return true;
        } else if (id == R.id.action_backend_restart) {
            runTermuxCommand(TermuxController.CMD_RESTART, true);
            return true;
        } else if (id == R.id.action_backend_stop) {
            confirmStopBackend(false);
            return true;
        } else if (id == R.id.action_backend_logs) {
            runTermuxCommand(TermuxController.CMD_LOGS, true);
            return true;
        } else if (id == R.id.action_toggle_fullscreen) {
            applyFullscreenState(!fullscreenActive, true);
            return true;
        } else if (id == R.id.action_copy_url) {
            String url = !TextUtils.isEmpty(webView.getUrl())
                    ? webView.getUrl()
                    : config.getBaseUrl();
            copyTextToClipboard("DSH URL", url);
            Toast.makeText(this, "已复制: " + url, Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_settings) {
            showSettingsDialog();
            return true;
        } else if (id == R.id.action_paste_auth_url) {
            showPasteAuthUrlDialog();
            return true;
        } else if (id == R.id.action_clear_cache) {
            webView.clearCache(true);
            Toast.makeText(this, "已清理页面缓存（Cookie 保留）", Toast.LENGTH_SHORT).show();
            webView.reload();
            return true;
        } else if (id == R.id.action_close_frontend) {
            Toast.makeText(this, "已关闭前端，DSH 后端继续运行", Toast.LENGTH_SHORT).show();
            closeFrontendOnly();
            return true;
        } else if (id == R.id.action_stop_and_exit) {
            confirmStopBackend(true);
            return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TERMUX_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String action = pendingTermuxAction;
            boolean interactive = pendingTermuxInteractive;
            pendingTermuxAction = null;
            if (granted) {
                termuxPermissionDenied = false;
                runTermuxCommand(
                        TextUtils.isEmpty(action) ? TermuxController.CMD_URL : action,
                        interactive);
            } else {
                termuxPermissionDenied = true;
                showTermuxHelpDialog("需要 Termux RUN_COMMAND 权限",
                        "未授予 com.termux.permission.RUN_COMMAND 权限，无法自动获取 DSH 认证令牌"
                                + "或控制 Termux 中的 DSH 后端。\n\n"
                                + "您可以改用菜单「粘贴认证链接」：在 Termux 中运行 dsh web，"
                                + "复制其输出的链接并粘贴即可。");
                if (offlinePanel.getVisibility() != View.VISIBLE) {
                    webView.loadUrl(config.getBaseUrl());
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    int count = clip.getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        if (requestCode == WebViewDownloadHelper.REQ_CREATE_DOCUMENT) {
            downloadHelper.onCreateDocumentResult(resultCode, data);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_F5
                    || (event.isCtrlPressed() && keyCode == KeyEvent.KEYCODE_R)) {
                if (offlinePanel.getVisibility() == View.VISIBLE) {
                    probeAndLoad(true);
                } else {
                    webView.reload();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_F11
                    || (event.isCtrlPressed() && event.isShiftPressed()
                    && keyCode == KeyEvent.KEYCODE_F)) {
                applyFullscreenState(!fullscreenActive, true);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (topBar.getVisibility() == View.VISIBLE) {
                    showMainMenu(menuButton);
                } else {
                    applyFullscreenState(false, true);
                }
                return true;
            }
            if (event.isAltPressed() && keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            if (event.isAltPressed() && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && webView.canGoForward()) {
                webView.goForward();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Immersive fullscreen first: back restores the top bar instead of leaving the app.
        if (fullscreenActive) {
            applyFullscreenState(false, true);
            return;
        }
        if (offlinePanel.getVisibility() != View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackPressMs < 1800L) {
            closeFrontendOnly();
        } else {
            lastBackPressMs = now;
            Toast.makeText(this,
                    "再按一次返回关闭前端（DSH 后端保持运行）", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Preserve WebView & WebSocket across tablet rotations, multi-window & external displays.
        if (rootLayout != null) {
            rootLayout.requestApplyInsets();
        }
        // A system light/dark switch must reach the page: with uiMode in configChanges the
        // WebView keeps its old prefers-color-scheme until the document is re-rendered, so
        // DSH's 外观 = 跟随系统 would otherwise stay on the previous scheme.
        boolean night = isNightMode();
        if (night != nightModeActive) {
            nightModeActive = night;
            applyTopBarColors();
            if (webView != null) {
                webView.setBackgroundColor(chromeBackgroundColor());
                webView.reload();
            }
        }
        syncSystemBars();
    }

    /** Repaint the custom chrome when the system light/dark theme changes. */
    private void applyTopBarColors() {
        int bg = chromeBackgroundColor();
        boolean light = isLightColor(bg);
        if (topBar != null) {
            topBar.setBackgroundColor(bg);
        }
        if (topBarDivider != null) {
            topBarDivider.setBackgroundColor(light ? 0xFFE2E8F0 : 0x33FFFFFF);
        }
        if (titleView != null) {
            titleView.setTextColor(light ? 0xFF0F172A : 0xFFF8FAFC);
        }
        if (subtitleView != null) {
            subtitleView.setTextColor(light ? 0xFF64748B : 0xFF94A3B8);
        }
        if (shellLayout != null) {
            shellLayout.setBackgroundColor(bg);
        }
        if (offlinePanel != null) {
            offlinePanel.setBackgroundColor(nightModeActive ? 0xFF0F172A : 0xFFF8FAFC);
        }
        syncSystemBars();
    }

    /**
     * Adopt the DSH page's own background so the top bar and the strip behind the status
     * bar blend seamlessly, including when the page theme (浅色/深色) differs from the system.
     */
    private void applyPageBackground(int color) {
        if (color == 0 || color == pageBackgroundColor) {
            return;
        }
        pageBackgroundColor = color;
        applyTopBarColors();
        if (webView != null) {
            webView.setBackgroundColor(color);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        TermuxController.setListener(null);
        cancelPendingFileChooser();
        CookieManager.getInstance().flush();
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void cancelPendingFileChooser() {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
    }

    private void openExternalUri(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开外部链接: " + uri, Toast.LENGTH_SHORT).show();
        }
    }

    private void copyTextToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    private static String[] resolveMimeTypes(String[] acceptTypes) {
        if (acceptTypes == null || acceptTypes.length == 0) {
            return new String[0];
        }
        List<String> list = new ArrayList<>();
        MimeTypeMap map = MimeTypeMap.getSingleton();
        for (String raw : acceptTypes) {
            if (TextUtils.isEmpty(raw)) {
                continue;
            }
            String[] parts = raw.split(",");
            for (String part : parts) {
                String item = part.trim();
                if (TextUtils.isEmpty(item) || "*/*".equals(item)) {
                    return new String[0];
                }
                if (item.startsWith(".")) {
                    String ext = item.substring(1).toLowerCase();
                    String mime = map.getMimeTypeFromExtension(ext);
                    if (!TextUtils.isEmpty(mime) && !list.contains(mime)) {
                        list.add(mime);
                    }
                } else if (item.contains("/")) {
                    if (!list.contains(item)) {
                        list.add(item);
                    }
                }
            }
        }
        return list.toArray(new String[0]);
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
