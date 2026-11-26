package com.deepseek.dshclient;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class TermuxController {
    public static final String TERMUX_PACKAGE = "com.termux";
    public static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    public static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    public static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    private static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";

    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";
    public static final String CMD_RESTART = "restart";
    public static final String CMD_STATUS = "status";
    public static final String CMD_LOGS = "logs";
    public static final String CMD_URL = "url";

    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    private static final AtomicInteger REQUEST_SEQ = new AtomicInteger(100);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile ResultListener activeListener;

    public interface ResultListener {
        void onCommandResult(CommandResult result);
    }

    public static final class CommandResult {
        public final String action;
        public final int requestId;
        public final boolean interactive;
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final int err;
        public final String errmsg;
        public final Map<String, String> fields;

        public CommandResult(
                String action,
                int requestId,
                boolean interactive,
                String stdout,
                String stderr,
                int exitCode,
                int err,
                String errmsg
        ) {
            this.action = action;
            this.requestId = requestId;
            this.interactive = interactive;
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
            this.err = err;
            this.errmsg = errmsg;
            this.fields = parseKeyValues(stdout);
        }

        public boolean hasPluginError() {
            return err > 0 || !TextUtils.isEmpty(errmsg);
        }

        public String getField(String key, String fallback) {
            String value = fields.get(key);
            return TextUtils.isEmpty(value) ? fallback : value;
        }

        public String getStatusValue() {
            return getField("STATUS", "");
        }

        private static Map<String, String> parseKeyValues(String text) {
            Map<String, String> map = new HashMap<>();
            if (TextUtils.isEmpty(text)) {
                return map;
            }
            String[] lines = text.split("\\r?\\n");
            for (String line : lines) {
                int idx = line.indexOf('=');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim();
                    if (isUpperKey(k)) {
                        String v = line.substring(idx + 1).trim();
                        map.put(k, v);
                    }
                }
            }
            return map;
        }

        private static boolean isUpperKey(String key) {
            if (TextUtils.isEmpty(key) || key.length() > 24) {
                return false;
            }
            for (int i = 0; i < key.length(); i++) {
                char c = key.charAt(i);
                if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                    return false;
                }
            }
            return true;
        }
    }

    private TermuxController() {
    }

    public static void setListener(ResultListener listener) {
        activeListener = listener;
    }

    static void dispatchResult(final CommandResult result) {
        final ResultListener listener = activeListener;
        if (listener == null) {
            return;
        }
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                ResultListener current = activeListener;
                if (current != null) {
                    current.onCommandResult(result);
                }
            }
        });
    }

    public static boolean isTermuxInstalled(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasRunCommandPermission(Context context) {
        return context.checkSelfPermission(PERMISSION_RUN_COMMAND)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static int execute(
            Context context,
            AppConfig config,
            String action,
            boolean interactive
    ) throws SecurityException, IllegalStateException {
        int requestId = REQUEST_SEQ.incrementAndGet();

        Intent resultIntent = new Intent(context, TermuxResultReceiver.class);
        resultIntent.setAction(TermuxResultReceiver.ACTION_TERMUX_RESULT);
        resultIntent.putExtra(TermuxResultReceiver.EXTRA_REQUEST_ACTION, action);
        resultIntent.putExtra(TermuxResultReceiver.EXTRA_REQUEST_ID, requestId);
        resultIntent.putExtra(TermuxResultReceiver.EXTRA_INTERACTIVE, interactive);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            pendingFlags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context.getApplicationContext(),
                requestId,
                resultIntent,
                pendingFlags
        );

        List<String> args = new ArrayList<>();
        args.add(action);
        args.add("--host");
        args.add(config.getHost());
        args.add("--port");
        args.add(String.valueOf(config.getPort()));
        args.add("--workdir");
        args.add(config.getWorkdir());
        if (CMD_LOGS.equals(action)) {
            args.add("--lines");
            args.add("150");
        }

        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, TERMUX_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_COMMAND_PATH, config.getScriptPath());
        intent.putExtra(EXTRA_ARGUMENTS, args.toArray(new String[0]));
        intent.putExtra(EXTRA_WORKDIR, TERMUX_HOME);
        intent.putExtra(EXTRA_BACKGROUND, true);
        intent.putExtra(EXTRA_COMMAND_LABEL, "DSH " + action);
        intent.putExtra(EXTRA_PENDING_INTENT, pendingIntent);

        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        return requestId;
    }
}
