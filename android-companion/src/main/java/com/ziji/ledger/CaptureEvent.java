package com.ziji.ledger;

public final class CaptureEvent {
    public final long timestampMillis;
    public final String status;
    public final String reason;
    public final String packageName;
    public final String text;

    public CaptureEvent(long timestampMillis, String status, String reason, String packageName, String text) {
        this.timestampMillis = timestampMillis;
        this.status = safe(status);
        this.reason = safe(reason);
        this.packageName = safe(packageName);
        this.text = safe(text);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
