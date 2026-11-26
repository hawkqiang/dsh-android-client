package com.deepseek.dshclient;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

public final class AppConfig {
    private static final String PREFS_NAME = "dsh_client_prefs";

    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_SCRIPT_PATH = "script_path";
    private static final String KEY_WORKDIR = "workdir";
    private static final String KEY_FULLSCREEN = "fullscreen";

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 3080;
    public static final String DEFAULT_SCRIPT_PATH =
            "/data/data/com.termux/files/home/.local/bin/dsh-termux-ctl";
    public static final String DEFAULT_WORKDIR =
            "/storage/emulated/0/dsh_project";

    private final SharedPreferences prefs;

    public AppConfig(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getHost() {
        String host = prefs.getString(KEY_HOST, DEFAULT_HOST);
        return TextUtils.isEmpty(host) ? DEFAULT_HOST : host.trim();
    }

    public int getPort() {
        int port = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        return (port > 0 && port <= 65535) ? port : DEFAULT_PORT;
    }

    public String getScriptPath() {
        String path = prefs.getString(KEY_SCRIPT_PATH, DEFAULT_SCRIPT_PATH);
        return TextUtils.isEmpty(path) ? DEFAULT_SCRIPT_PATH : path.trim();
    }

    public String getWorkdir() {
        String dir = prefs.getString(KEY_WORKDIR, DEFAULT_WORKDIR);
        return TextUtils.isEmpty(dir) ? DEFAULT_WORKDIR : dir.trim();
    }

    public boolean isFullscreen() {
        return prefs.getBoolean(KEY_FULLSCREEN, false);
    }

    public void setFullscreen(boolean fullscreen) {
        prefs.edit().putBoolean(KEY_FULLSCREEN, fullscreen).apply();
    }

    public void save(String host, int port, String scriptPath, String workdir) {
        String cleanHost = TextUtils.isEmpty(host) ? DEFAULT_HOST : host.trim();
        int cleanPort = (port > 0 && port <= 65535) ? port : DEFAULT_PORT;
        String cleanScript = TextUtils.isEmpty(scriptPath) ? DEFAULT_SCRIPT_PATH : scriptPath.trim();
        String cleanWorkdir = TextUtils.isEmpty(workdir) ? DEFAULT_WORKDIR : workdir.trim();

        prefs.edit()
                .putString(KEY_HOST, cleanHost)
                .putInt(KEY_PORT, cleanPort)
                .putString(KEY_SCRIPT_PATH, cleanScript)
                .putString(KEY_WORKDIR, cleanWorkdir)
                .apply();
    }

    public void resetDefaults() {
        prefs.edit()
                .putString(KEY_HOST, DEFAULT_HOST)
                .putInt(KEY_PORT, DEFAULT_PORT)
                .putString(KEY_SCRIPT_PATH, DEFAULT_SCRIPT_PATH)
                .putString(KEY_WORKDIR, DEFAULT_WORKDIR)
                .apply();
    }

    public String getBaseUrl() {
        return "http://" + getHost() + ":" + getPort();
    }

    public boolean isLocalDshUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (TextUtils.isEmpty(host)) {
                return false;
            }
            return host.equalsIgnoreCase(getHost())
                    || "127.0.0.1".equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host);
        } catch (Exception ignored) {
            return false;
        }
    }
}
