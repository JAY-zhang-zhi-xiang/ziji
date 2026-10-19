package com.ziji.ledger;

public final class BridgeSettings {
    public static final String PREFS = "ziji_bridge";
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_PENDING_CAPTURES = "pending_captures";
    public static final String KEY_LOCAL_LEDGER = "local_ledger";
    public static final String KEY_CAPTURE_HISTORY = "capture_history";
    public static final String DEFAULT_ENDPOINT = "";
    public static final int MAX_PENDING_CAPTURES = 50;
    public static final int MAX_LOCAL_ENTRIES = 2000;
    public static final int MAX_CAPTURE_HISTORY = 30;

    private BridgeSettings() {
    }
}
