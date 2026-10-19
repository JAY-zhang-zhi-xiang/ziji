package com.ziji.ledger;

public final class LedgerMigrationReport {
    public final boolean migrated;
    public final boolean backupCreated;
    public final String backupPath;
    public final String legacyChecksum;
    public final int transactionCount;
    public final int captureHistoryCount;
    public final int pendingCount;
    public final long transactionTotalCents;
    public final String validationMessage;

    public LedgerMigrationReport(
            boolean migrated,
            boolean backupCreated,
            String backupPath,
            String legacyChecksum,
            int transactionCount,
            int captureHistoryCount,
            int pendingCount,
            long transactionTotalCents,
            String validationMessage
    ) {
        this.migrated = migrated;
        this.backupCreated = backupCreated;
        this.backupPath = backupPath == null ? "" : backupPath;
        this.legacyChecksum = legacyChecksum == null ? "" : legacyChecksum;
        this.transactionCount = transactionCount;
        this.captureHistoryCount = captureHistoryCount;
        this.pendingCount = pendingCount;
        this.transactionTotalCents = transactionTotalCents;
        this.validationMessage = validationMessage == null ? "" : validationMessage;
    }
}
