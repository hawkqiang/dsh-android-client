package com.deepseek.dshclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public final class TermuxResultReceiver extends BroadcastReceiver {
    public static final String ACTION_TERMUX_RESULT =
            "com.deepseek.dshclient.ACTION_TERMUX_RESULT";
    public static final String EXTRA_REQUEST_ACTION = "dsh_request_action";
    public static final String EXTRA_REQUEST_ID = "dsh_request_id";
    public static final String EXTRA_INTERACTIVE = "dsh_interactive";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getStringExtra(EXTRA_REQUEST_ACTION);
        int requestId = intent.getIntExtra(EXTRA_REQUEST_ID, 0);
        boolean interactive = intent.getBooleanExtra(EXTRA_INTERACTIVE, true);

        Bundle resultBundle = intent.getBundleExtra("result");
        String stdout = "";
        String stderr = "";
        int exitCode = -1;
        int err = -1;
        String errmsg = "";

        if (resultBundle != null) {
            stdout = safeString(resultBundle.getString("stdout"));
            stderr = safeString(resultBundle.getString("stderr"));
            exitCode = resultBundle.getInt("exitCode", -1);
            err = resultBundle.getInt("err", -1);
            errmsg = safeString(resultBundle.getString("errmsg"));
        }

        TermuxController.CommandResult result = new TermuxController.CommandResult(
                action != null ? action : "unknown",
                requestId,
                interactive,
                stdout,
                stderr,
                exitCode,
                err,
                errmsg
        );
        TermuxController.dispatchResult(result);
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }
}
