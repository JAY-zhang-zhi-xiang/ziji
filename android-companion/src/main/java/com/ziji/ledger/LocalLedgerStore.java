package com.ziji.ledger;

import android.content.Context;

import java.util.List;

public final class LocalLedgerStore {
    private LocalLedgerStore() {
    }

    public static synchronized LocalLedger load(Context context) {
        return LedgerAppStorage.repository(context).loadLedger(BridgeSettings.MAX_LOCAL_ENTRIES);
    }

    public static synchronized boolean add(Context context, LedgerEntry entry) {
        return LedgerAppStorage.repository(context).addTransaction(entry);
    }

    /** 与 {@link #add} 相同，但能区分「重复」和「写盘失败」。 */
    public static synchronized LedgerRepository.AddResult addResult(Context context, LedgerEntry entry) {
        return LedgerAppStorage.repository(context).addTransactionResult(entry);
    }

    public static synchronized LedgerRepository.AddResult addResultWithPending(
            Context context, LedgerEntry entry, String pendingText) {
        return LedgerAppStorage.repository(context).addTransactionAndQueue(entry, pendingText);
    }

    /**
     * 人工录入（手动记账）入库：不做同笔合并。
     *
     * 用户手动记两笔同额同店的钱，是两笔真实流水，不能因为「看起来像重复」被吞掉。
     */
    public static synchronized LedgerRepository.AddResult addExplicit(Context context, LedgerEntry entry) {
        return LedgerAppStorage.repository(context).addExplicitTransactionResult(entry);
    }

    public static synchronized LedgerRepository.AddResult addExplicitWithPending(
            Context context, LedgerEntry entry, String pendingText) {
        return LedgerAppStorage.repository(context).addExplicitTransactionAndQueue(entry, pendingText);
    }

    public static synchronized LedgerRepository.AddResult mergeCaptureIntoExisting(
            Context context, String existingId, LedgerEntry candidate) {
        return LedgerAppStorage.repository(context).mergeCaptureIntoExisting(existingId, candidate);
    }

    /** 批量入库，只写一次盘。CSV 导入用。 */
    public static synchronized int addAll(Context context, List<LedgerEntry> entries) {
        return LedgerAppStorage.repository(context).addTransactions(entries);
    }

    public static synchronized boolean update(Context context, LedgerEntry entry) {
        return LedgerAppStorage.repository(context).updateTransaction(entry);
    }

    public static synchronized boolean remove(Context context, String id) {
        return LedgerAppStorage.repository(context).removeTransaction(id);
    }

    /** 记住「这个商户属于哪一类」，下次自动套用。 */
    public static synchronized boolean learnMerchantCategory(Context context, String merchant, String category) {
        return LedgerAppStorage.repository(context).learnMerchantCategory(merchant, category);
    }

    /** 记住「这个商品属于哪一类」，下次同商品自动套用（平台商户下按商品区分）。 */
    public static synchronized boolean learnProductCategory(Context context, String note, String category) {
        return LedgerAppStorage.repository(context).learnProductCategory(note, category);
    }

    /** 把同商户的历史「其他」记录一并归类，返回改动的条数。 */
    public static synchronized int backfillCategory(Context context, String merchant, String category) {
        return LedgerAppStorage.repository(context).backfillCategory(merchant, category);
    }

    public static synchronized void save(Context context, LocalLedger ledger) {
        LedgerAppStorage.repository(context).replaceLedger(ledger);
    }

    // 下面三个走全量账本，不受 MAX_LOCAL_ENTRIES 截断影响。
    // 账本超过读取上限后，用 LocalLedger 算「本月支出」「账单数」都会偏小。

    public static synchronized long expenseTotalSince(Context context, long startMillis) {
        return LedgerAppStorage.repository(context).expenseTotalSince(startMillis);
    }

    public static synchronized int transactionCount(Context context) {
        return LedgerAppStorage.repository(context).transactionCount();
    }

    /** 全量用途覆盖：[已知用途, 总条数]。与 {@link #transactionCount} 同一口径。 */
    public static synchronized int[] purposeCoverage(Context context) {
        return LedgerAppStorage.repository(context).purposeCoverage();
    }

    /** 把历史记录里的旧分类名归一（住房→居家、教育→学习）。返回改写条数。 */
    public static synchronized int migrateCategoryAliases(Context context) {
        return LedgerAppStorage.repository(context).migrateCategoryAliases();
    }

    public static synchronized List<LedgerEntry> entriesSince(Context context, long startMillis) {
        return LedgerAppStorage.repository(context).entriesSince(startMillis);
    }

    public static synchronized List<LedgerEntry> entriesBetween(Context context, long startMillis, long endMillis) {
        return LedgerAppStorage.repository(context).entriesBetween(startMillis, endMillis);
    }

    /** 还没写清用途的记录，最近的在前；limit 为 0 表示不限。跨月，不受读取上限影响。 */
    public static synchronized List<LedgerEntry> pendingPurposes(Context context, int limit) {
        return LedgerAppStorage.repository(context).entriesNeedingPurpose(limit);
    }
}
