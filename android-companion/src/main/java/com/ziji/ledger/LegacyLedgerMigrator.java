package com.ziji.ledger;

import java.util.ArrayList;
import java.util.List;

public final class LegacyLedgerMigrator {
    private LegacyLedgerMigrator() {
    }

    public static Result migrate(LegacyLedgerSnapshot legacy) {
        LocalLedger ledger = LocalLedger.fromStoredString(legacy.localLedger, legacy.maxLedgerEntries);
        CaptureHistory history = CaptureHistory.fromStoredString(legacy.captureHistory, legacy.maxCaptureHistoryEntries);
        PendingCaptureQueue pending = PendingCaptureQueue.fromStoredString(legacy.pendingCaptures, legacy.maxPendingCaptures);

        List<LedgerEntry> transactions = new ArrayList<>(ledger.entries());
        List<CaptureEvent> captureHistory = new ArrayList<>(history.entries());
        List<String> pendingCaptures = new ArrayList<>(pending.items());
        LedgerRepositorySnapshot snapshot = new LedgerRepositorySnapshot(
                transactions,
                captureHistory,
                pendingCaptures,
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                legacy.checksum(),
                true,
                System.currentTimeMillis()
        );

        String validationMessage = validate(snapshot, ledger.size(), history.size(), pending.size());
        LedgerMigrationReport report = new LedgerMigrationReport(
                true,
                false,
                "",
                legacy.checksum(),
                snapshot.transactions.size(),
                snapshot.captureHistory.size(),
                snapshot.pendingCaptures.size(),
                snapshot.transactionTotalCents(),
                validationMessage
        );
        return new Result(snapshot, report);
    }

    private static String validate(LedgerRepositorySnapshot snapshot, int ledgerCount, int captureCount, int pendingCount) {
        if (snapshot.transactions.size() != ledgerCount) return "transaction_count_mismatch";
        if (snapshot.captureHistory.size() != captureCount) return "capture_history_count_mismatch";
        if (snapshot.pendingCaptures.size() != pendingCount) return "pending_count_mismatch";
        return "ok";
    }

    public static final class Result {
        public final LedgerRepositorySnapshot snapshot;
        public final LedgerMigrationReport report;

        private Result(LedgerRepositorySnapshot snapshot, LedgerMigrationReport report) {
            this.snapshot = snapshot;
            this.report = report;
        }
    }
}
