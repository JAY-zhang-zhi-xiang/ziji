package com.ziji.ledger;

public final class ListenerStatus {
    public final String state;
    public final String detail;
    public final long updatedAtMillis;

    public ListenerStatus(String state, String detail, long updatedAtMillis) {
        this.state = safe(state);
        this.detail = safe(detail);
        this.updatedAtMillis = updatedAtMillis;
    }

    public static ListenerStatus empty() {
        return new ListenerStatus("unknown", "", 0L);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
