package com.ziji.ledger;

import android.content.Context;

public final class CaptureHistoryStore {
    private CaptureHistoryStore() {
    }

    public static synchronized CaptureHistory load(Context context) {
        return LedgerAppStorage.repository(context).loadCaptureHistory(BridgeSettings.MAX_CAPTURE_HISTORY);
    }

    public static synchronized void add(Context context, CaptureEvent event) {
        LedgerAppStorage.repository(context).addCaptureEvent(event);
    }
}
