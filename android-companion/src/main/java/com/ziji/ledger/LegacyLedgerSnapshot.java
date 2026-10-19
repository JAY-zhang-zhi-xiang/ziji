package com.ziji.ledger;

public final class LegacyLedgerSnapshot {
    public final String localLedger;
    public final String captureHistory;
    public final String pendingCaptures;
    public final int maxLedgerEntries;
    public final int maxCaptureHistoryEntries;
    public final int maxPendingCaptures;

    public LegacyLedgerSnapshot(
            String localLedger,
            String captureHistory,
            String pendingCaptures,
            int maxLedgerEntries,
            int maxCaptureHistoryEntries,
            int maxPendingCaptures
    ) {
        this.localLedger = safe(localLedger);
        this.captureHistory = safe(captureHistory);
        this.pendingCaptures = safe(pendingCaptures);
        this.maxLedgerEntries = Math.max(1, maxLedgerEntries);
        this.maxCaptureHistoryEntries = Math.max(1, maxCaptureHistoryEntries);
        this.maxPendingCaptures = Math.max(1, maxPendingCaptures);
    }

    public boolean isEmpty() {
        return localLedger.trim().isEmpty()
                && captureHistory.trim().isEmpty()
                && pendingCaptures.trim().isEmpty();
    }

    public String checksum() {
        return LedgerCodec.checksum(localLedger, captureHistory, pendingCaptures);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
