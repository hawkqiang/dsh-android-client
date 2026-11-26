package com.deepseek.dshclient;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebViewDownloadHelper {
    public static final String JS_NAME = "DshNativeDownload";
    public static final int REQ_CREATE_DOCUMENT = 2002;

    private static final Pattern FILENAME_STAR =
            Pattern.compile("filename\\*\\s*=\\s*(?:UTF-8''|utf-8'')([^;\\r\\n]+)");
    private static final Pattern FILENAME_PLAIN =
            Pattern.compile("filename\\s*=\\s*\"?([^\";\\r\\n]+)\"?");

    private final Activity activity;
    private final AppConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String currentUrl = "";

    // Pending payload for API 26-28 ACTION_CREATE_DOCUMENT fallback.
    private byte[] pendingBytes;
    private String pendingHttpUrl;
    private String pendingUserAgent;

    public WebViewDownloadHelper(Activity activity, AppConfig config) {
        this.activity = activity;
        this.config = config;
    }

    public void setCurrentUrl(String url) {
        this.currentUrl = url != null ? url : "";
    }

    public void handleDownload(
            WebView webView,
            String url,
            String userAgent,
            String contentDisposition,
            String mimeType
    ) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        String fileName = extractFileName(url, contentDisposition, mimeType);
        String resolvedMime = TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType;

        if (url.startsWith("data:")) {
            saveDataUrl(url, fileName, resolvedMime);
        } else if (url.startsWith("blob:")) {
            fetchBlobViaJs(webView, url, fileName, resolvedMime);
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            downloadHttpUrl(url, userAgent, fileName, resolvedMime);
        } else {
            showToast("不支持的下载协议: " + url);
        }
    }

    private void fetchBlobViaJs(WebView webView, String blobUrl, String fileName, String mimeType) {
        String js = "(function(){"
                + "fetch(" + jsQuote(blobUrl) + ").then(function(r){return r.blob();}).then(function(b){"
                + "  var reader = new FileReader();"
                + "  reader.onloadend = function() {"
                + "    if (window.DshNativeDownload) {"
                + "      window.DshNativeDownload.onBlobData(String(reader.result||''), "
                + jsQuote(fileName) + ", " + jsQuote(mimeType) + ");"
                + "    }"
                + "  };"
                + "  reader.readAsDataURL(b);"
                + "}).catch(function(e){"
                + "  if (window.DshNativeDownload) window.DshNativeDownload.onBlobError(String(e));"
                + "});"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    @JavascriptInterface
    public void onBlobData(final String dataUrl, final String fileName, final String mimeType) {
        if (!config.isLocalDshUrl(currentUrl)) {
            return;
        }
        saveDataUrl(dataUrl, sanitizeFileName(fileName), mimeType);
    }

    @JavascriptInterface
    public void onBlobError(final String error) {
        if (!config.isLocalDshUrl(currentUrl)) {
            return;
        }
        showToast("导出文件失败: " + error);
    }

    private void saveDataUrl(final String dataUrl, final String fileName, final String fallbackMime) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int commaIdx = dataUrl.indexOf(',');
                    if (commaIdx <= 5) {
                        showToast("无效的数据下载内容");
                        return;
                    }
                    String header = dataUrl.substring(5, commaIdx);
                    String payload = dataUrl.substring(commaIdx + 1);
                    String mime = fallbackMime;
                    int semiIdx = header.indexOf(';');
                    if (semiIdx > 0) {
                        String parsedMime = header.substring(0, semiIdx).trim();
                        if (!TextUtils.isEmpty(parsedMime)) {
                            mime = parsedMime;
                        }
                    } else if (!TextUtils.isEmpty(header) && !header.contains("base64")) {
                        mime = header.trim();
                    }

                    byte[] bytes;
                    if (header.contains(";base64")) {
                        bytes = Base64.decode(payload, Base64.DEFAULT);
                    } else {
                        String decoded = URLDecoder.decode(payload, "UTF-8");
                        bytes = decoded.getBytes(StandardCharsets.UTF_8);
                    }

                    if (Build.VERSION.SDK_INT >= 29) {
                        saveStreamToMediaStore(
                                new ByteArrayInputStream(bytes), fileName, mime);
                    } else {
                        promptCreateDocumentForBytes(bytes, fileName, mime);
                    }
                } catch (Exception e) {
                    showToast("保存文件失败: " + e.getMessage());
                }
            }
        }, "dsh-dl-data").start();
    }

    private void downloadHttpUrl(
            final String urlStr,
            final String userAgent,
            final String initialFileName,
            final String initialMime
    ) {
        showToast("正在下载: " + initialFileName);
        if (Build.VERSION.SDK_INT < 29) {
            pendingBytes = null;
            pendingHttpUrl = urlStr;
            pendingUserAgent = userAgent;
            launchCreateDocument(initialFileName, initialMime);
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = openConnection(urlStr, userAgent);
                    int status = conn.getResponseCode();
                    if (status < 200 || status >= 300) {
                        showToast("下载失败 (HTTP " + status + ")");
                        return;
                    }
                    String disp = conn.getHeaderField("Content-Disposition");
                    String contentType = conn.getContentType();
                    String mime = !TextUtils.isEmpty(contentType)
                            ? contentType.split(";")[0].trim()
                            : initialMime;
                    String finalName = extractFileName(urlStr, disp, mime);
                    if (TextUtils.isEmpty(finalName)) {
                        finalName = initialFileName;
                    }
                    try (InputStream in = conn.getInputStream()) {
                        saveStreamToMediaStore(in, finalName, mime);
                    }
                } catch (Exception e) {
                    showToast("下载失败: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }, "dsh-dl-http").start();
    }

    private HttpURLConnection openConnection(String urlStr, String userAgent) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        String cookies = CookieManager.getInstance().getCookie(urlStr);
        if (!TextUtils.isEmpty(cookies)) {
            conn.setRequestProperty("Cookie", cookies);
        }
        if (!TextUtils.isEmpty(userAgent)) {
            conn.setRequestProperty("User-Agent", userAgent);
        }
        return conn;
    }

    private void saveStreamToMediaStore(InputStream in, String fileName, String mimeType)
            throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            return;
        }
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, sanitizeFileName(fileName));
        values.put(MediaStore.Downloads.MIME_TYPE,
                TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.Downloads.IS_PENDING, 1);

        Uri itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (itemUri == null) {
            throw new IOException("无法创建 Download 文件");
        }
        try {
            try (OutputStream out = resolver.openOutputStream(itemUri)) {
                if (out == null) {
                    throw new IOException("无法打开输出流");
                }
                copyStream(in, out);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(itemUri, done, null, null);
            showToast("已保存到 Download: " + fileName);
        } catch (IOException e) {
            resolver.delete(itemUri, null, null);
            throw e;
        }
    }

    private void promptCreateDocumentForBytes(
            final byte[] bytes, final String fileName, final String mimeType) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                pendingBytes = bytes;
                pendingHttpUrl = null;
                pendingUserAgent = null;
                launchCreateDocument(fileName, mimeType);
            }
        });
    }

    private void launchCreateDocument(String fileName, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(TextUtils.isEmpty(mimeType) ? "*/*" : mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, sanitizeFileName(fileName));
        try {
            activity.startActivityForResult(intent, REQ_CREATE_DOCUMENT);
        } catch (Exception e) {
            showToast("无法打开系统保存选择器: " + e.getMessage());
        }
    }

    public void onCreateDocumentResult(int resultCode, Intent data) {
        final byte[] bytes = pendingBytes;
        final String httpUrl = pendingHttpUrl;
        final String ua = pendingUserAgent;
        pendingBytes = null;
        pendingHttpUrl = null;
        pendingUserAgent = null;

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri targetUri = data.getData();
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try (OutputStream out = activity.getContentResolver().openOutputStream(targetUri)) {
                    if (out == null) {
                        showToast("无法写入目标文件");
                        return;
                    }
                    if (bytes != null) {
                        out.write(bytes);
                        out.flush();
                        showToast("文件已保存");
                    } else if (!TextUtils.isEmpty(httpUrl)) {
                        conn = openConnection(httpUrl, ua);
                        try (InputStream in = conn.getInputStream()) {
                            copyStream(in, out);
                        }
                        showToast("下载完成");
                    }
                } catch (Exception e) {
                    showToast("保存失败: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }, "dsh-dl-uri").start();
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String extractFileName(String url, String contentDisposition, String mimeType) {
        if (!TextUtils.isEmpty(contentDisposition)) {
            Matcher mStar = FILENAME_STAR.matcher(contentDisposition);
            if (mStar.find()) {
                try {
                    return sanitizeFileName(URLDecoder.decode(mStar.group(1).trim(), "UTF-8"));
                } catch (Exception ignored) {
                }
            }
            Matcher mPlain = FILENAME_PLAIN.matcher(contentDisposition);
            if (mPlain.find()) {
                return sanitizeFileName(mPlain.group(1).trim());
            }
        }
        String guessed = URLUtil.guessFileName(url, contentDisposition, mimeType);
        return sanitizeFileName(TextUtils.isEmpty(guessed) ? "download.bin" : guessed);
    }

    private static String sanitizeFileName(String name) {
        if (TextUtils.isEmpty(name)) {
            return "download.bin";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        return TextUtils.isEmpty(cleaned) ? "download.bin" : cleaned;
    }

    private static String jsQuote(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c); break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private void showToast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
