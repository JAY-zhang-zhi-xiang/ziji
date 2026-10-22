package com.ziji.ledger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LedgerRepositorySnapshot {
    public final List<LedgerEntry> transactions;
    public final List<CaptureEvent> captureHistory;
    public final List<String> pendingCaptures;
    public final List<PendingCaptureQueue.Item> pendingCaptureItems;
    public final List<String> rules;
    public final List<String> accounts;
    public final List<String> categories;
    public final String legacyChecksum;
    public final boolean migrationComplete;
    public final long migratedAtMillis;

    public LedgerRepositorySnapshot(
            List<LedgerEntry> transactions,
            List<CaptureEvent> captureHistory,
            List<String> pendingCaptures,
            List<String> rules,
            List<String> accounts,
            List<String> categories,
            String legacyChecksum,
            boolean migrationComplete,
            long migratedAtMillis
    ) {
        this(transactions, captureHistory, pendingCaptures, null, rules, accounts, categories,
                legacyChecksum, migrationComplete, migratedAtMillis);
    }

    public LedgerRepositorySnapshot(
            List<LedgerEntry> transactions,
            List<CaptureEvent> captureHistory,
            List<String> pendingCaptures,
            List<PendingCaptureQueue.Item> pendingCaptureItems,
            List<String> rules,
            List<String> accounts,
            List<String> categories,
            String legacyChecksum,
            boolean migrationComplete,
            long migratedAtMillis
    ) {
        this.transactions = immutableCopy(transactions);
        this.captureHistory = immutableCopy(captureHistory);
        this.pendingCaptures = immutableCopyStrings(pendingCaptures);
        this.pendingCaptureItems = Collections.unmodifiableList(new ArrayList<>(pendingCaptureItems == null
                ? PendingCaptureQueue.fromItems(this.pendingCaptures, Math.max(this.pendingCaptures.size(), 1)).records()
                : pendingCaptureItems));
        this.rules = immutableCopyStrings(rules);
        this.accounts = immutableCopyStrings(accounts);
        this.categories = immutableCopyStrings(categories);
        this.legacyChecksum = legacyChecksum == null ? "" : legacyChecksum;
        this.migrationComplete = migrationComplete;
        this.migratedAtMillis = migratedAtMillis;
    }

    public static LedgerRepositorySnapshot empty() {
        return new LedgerRepositorySnapshot(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), "", false, 0L);
    }

    public LedgerRepositorySnapshot withMigration(String checksum, long migratedAtMillis) {
        return new LedgerRepositorySnapshot(
                transactions,
                captureHistory,
                pendingCaptures,
                pendingCaptureItems,
                rules,
                accounts,
                categories,
                checksum,
                true,
                migratedAtMillis
        );
    }

    public long transactionTotalCents() {
        long total = 0;
        for (LedgerEntry entry : transactions) {
            if (entry != null && entry.isExpense()) {
                total += entry.amountCents;
            }
        }
        return total;
    }

    private static <T> List<T> immutableCopy(List<T> input) {
        return Collections.unmodifiableList(new ArrayList<>(input == null ? new ArrayList<>() : input));
    }

    private static List<String> immutableCopyStrings(List<String> input) {
        return Collections.unmodifiableList(new ArrayList<>(input == null ? new ArrayList<>() : input));
    }
}
