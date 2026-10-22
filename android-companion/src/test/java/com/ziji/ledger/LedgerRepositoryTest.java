package com.ziji.ledger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class LedgerRepositoryTest {
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        migrateLegacyOnceAndKeepBackup();
        recoverFromCorruptMainFile();
        persistCrudHistoryAndPendingQueue();
        backupRoundTripKeepsLedgerRulesAndQueue();
        emptyBackupNeverClearsLedger();
        keepsLaterIdenticalPayments();
        stableNotificationEventsAreIdempotentWithoutDroppingNewPayments();
        mergesCrossChannelDuplicate();
        mergesExistingDuplicatesInLedger();
        mergeConfirmedDuplicateHandlesReversedIndexes();
        historicalDuplicateCandidatesRequireConfirmation();
        preservesSameMinuteDifferentMerchantsDuringCleanup();
        incrementalWriteKeepsHistory();
        writeFailureIsNotReportedAsDuplicate();
        cacheAndRecoveryStayConsistent();
        aggregatesIgnoreReadLimit();
        purposeSurvivesRoundTrip();
        legacyRowsWithoutPurposeStillLoad();
        corruptTransactionRowIsDropped();
        learnsMerchantCategory();
        learnsProductCategory();
        atomicPendingCaptureAppendAndRemove();
        pendingDuplicateCaptureSurvivesHistoryPruning();
        backfillsHistoryWhenCategoryIsLearned();
        editingKeepsSourceAndPurpose();
        keepsIdenticalManualEntries();
        explicitlyReviewedCandidateCanMergeOrRemainDistinct();
        System.out.println("ledger repository tests passed: " + checks);
    }

    private static void migrateLegacyOnceAndKeepBackup() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        LocalLedger legacyLedger = new LocalLedger(10);
        legacyLedger.add(TransactionParser.parse("com.tencent.mm", "微信支付收款助手 你已向 美团外卖 支付 ¥46.80", 1000L));
        CaptureHistory legacyHistory = new CaptureHistory(10);
        legacyHistory.add(new CaptureEvent(1000L, "captured", "captured", "com.tencent.mm", "微信支付收款助手 你已向 美团外卖 支付 ¥46.80"));
        PendingCaptureQueue legacyQueue = new PendingCaptureQueue(10);
        legacyQueue.add("支付宝 向 星巴克 付款 32.00元");

        LegacyLedgerSnapshot legacy = new LegacyLedgerSnapshot(
                legacyLedger.toStoredString(),
                legacyHistory.toStoredString(),
                legacyQueue.toStoredString(),
                10,
                10,
                10
        );

        LedgerMigrationReport report = repository.migrateLegacy(legacy);
        assertTrue(report.migrated, "legacy migration should succeed");
        assertTrue(report.backupCreated, "backup should be created");
        assertEquals(1, report.transactionCount, "transaction count should match");
        assertEquals(1, report.captureHistoryCount, "capture count should match");
        assertEquals(1, report.pendingCount, "pending count should match");
        assertTrue(new File(report.backupPath).exists(), "legacy backup should exist");

        LedgerMigrationReport second = repository.migrateLegacy(legacy);
        assertTrue(second.migrated, "second migration should be accepted");
        assertFalse(second.backupCreated, "second migration should be idempotent");
        assertEquals("already_migrated", second.validationMessage, "second migration should short circuit");
    }

    private static void recoverFromCorruptMainFile() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        repository.addTransaction(TransactionParser.parse("com.eg.android.alipaygphone", "支付宝 向 星巴克 付款 32.00元", 2000L));

        File main = new File(dir, "ledger-store-v2.db");
        Files.write(main.toPath(), "broken".getBytes(StandardCharsets.UTF_8));
        LedgerRepositorySnapshot snapshot = repository.snapshot();
        assertEquals(1, snapshot.transactions.size(), "backup should restore main file");

        File quarantineDir = dir;
        File[] quarantined = quarantineDir.listFiles((file, name) -> name.startsWith("ledger-store-v2.db.corrupt-"));
        assertTrue(quarantined != null && quarantined.length >= 1, "corrupt file should be quarantined");
    }

    private static void persistCrudHistoryAndPendingQueue() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        LedgerEntry entry = TransactionParser.parse("com.tencent.mm", "微信支付收款助手 你已向 瑞幸咖啡 支付 ¥18.00", 3000L);
        assertTrue(repository.addTransaction(entry), "transaction should be inserted");
        assertFalse(repository.addTransaction(entry), "duplicate transaction should be rejected");
        LedgerEntry updated = new LedgerEntry(entry.id, 2000L, "expense", "瑞幸咖啡", "微信", "餐饮", 3000L, entry.rawText, entry.sourcePackage, entry.sourceType, entry.fingerprint, entry.createdAtMillis, entry.updatedAtMillis, 0L, true);
        assertTrue(repository.updateTransaction(updated), "update should succeed");
        assertTrue(repository.removeTransaction(entry.id), "remove should succeed");

        repository.addCaptureEvent(new CaptureEvent(4000L, "captured", "captured", "com.tencent.mm", "微信支付收款助手"));
        repository.addCaptureEvent(new CaptureEvent(5000L, "duplicate", "duplicate", "com.tencent.mm", "微信支付收款助手"));
        CaptureHistory history = repository.loadCaptureHistory(10);
        assertEquals(2, history.size(), "capture history should persist");

        PendingCaptureQueue queue = new PendingCaptureQueue(10);
        queue.add("微信 支付 46.80元");
        queue.add("微信 支付 46.80元");
        queue.add("支付宝 向 星巴克 付款 32.00元");
        repository.savePendingQueue(queue);
        PendingCaptureQueue restored = repository.loadPendingQueue(10);
        assertEquals(2, restored.size(), "pending queue should dedupe duplicates");
    }

    /**
     * 空备份不是「恢复成空账本」。
     *
     * 用户选错一个 0 字节文件时，旧实现会把空状态写进主文件并返回 true，
     * 界面提示「账本已恢复」——账本和 .bak 同时变空，连回滚都没有。
     */
    private static void emptyBackupNeverClearsLedger() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        repository.addTransaction(TransactionParser.parse(
                "com.tencent.mm", "微信支付 向 星巴克 支付 ¥32.00", 1_000_000L));
        assertEquals(1, repository.transactionCount(), "precondition: one transaction");

        assertFalse(repository.importBackup(""), "empty string must be rejected");
        assertFalse(repository.importBackup(null), "null must be rejected");
        assertFalse(repository.importBackup("   \n\t  "), "blank text must be rejected");
        assertFalse(repository.importBackup("ZIJI_LEDGER_DB\t1\nSECTION\ttransactions\n"),
                "header-only backup has no content and must be rejected");

        assertEquals(1, repository.transactionCount(), "ledger must survive rejected backups");
        assertTrue(new File(dir, "ledger-store-v2.db.bak").length() > 0,
                "backup file must still contain data");

        // 真正有内容的备份仍然要能恢复，别为了防呆把正常路径也堵死。
        String backup = repository.exportBackup();
        LedgerRepository other = LedgerRepository.open(tempDir());
        assertTrue(other.importBackup(backup), "valid backup must still restore");
        assertEquals(1, other.transactionCount(), "valid backup restores its transaction");
    }

    private static void backupRoundTripKeepsLedgerRulesAndQueue() throws Exception {
        File sourceDir = tempDir();
        LedgerRepository source = LedgerRepository.open(sourceDir);
        source.addTransaction(TransactionParser.parse("com.tencent.mm", "微信支付 向 瑞幸咖啡 支付 ¥18.00", 6000L));
        source.replaceRules(java.util.Collections.singletonList("瑞幸\t咖啡"));
        PendingCaptureQueue queue = new PendingCaptureQueue(10);
        queue.add("微信支付 向 瑞幸咖啡 支付 ¥18.00");
        source.savePendingQueue(queue);
        String pendingId = source.snapshot().pendingCaptureItems.get(0).id;

        LedgerRepository replacement = LedgerRepository.open(tempDir());
        assertTrue(replacement.replaceAll(source.snapshot()), "snapshot replacement succeeds");
        assertEquals(pendingId, replacement.snapshot().pendingCaptureItems.get(0).id,
                "snapshot replacement preserves outbox event identity");

        String backup = source.exportBackup();
        LedgerRepository restored = LedgerRepository.open(tempDir());
        assertTrue(restored.importBackup(backup), "backup should restore");
        LedgerRepositorySnapshot snapshot = restored.snapshot();
        assertEquals(1, snapshot.transactions.size(), "backup should keep transactions");
        assertEquals("瑞幸\t咖啡", snapshot.rules.get(0), "backup should keep rules");
        assertEquals(1, snapshot.pendingCaptures.size(), "backup should keep pending sync queue");
        assertEquals(pendingId, snapshot.pendingCaptureItems.get(0).id,
                "backup restore preserves pending event identity");
    }

    /**
     * 用户报告的场景：转出 0.01 之后，后续相同金额的支付应当仍能入账。
     * 旧实现用「指纹」无条件去重，而指纹不含时间戳，会把后一笔永久吞掉。
     */
    private static void keepsLaterIdenticalPayments() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        LedgerEntry first = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                1_000_000L
        );
        assertEquals("expense", first.direction, "transfer out must be expense");
        assertTrue(repository.addTransaction(first), "first transfer should be stored");

        // 同一笔在极短时间内重复投递：应当被去重。
        LedgerEntry immediateRepeat = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                1_030_000L
        );
        assertFalse(immediateRepeat.fingerprint.isEmpty(), "fingerprint should be present");
        assertFalse(repository.addTransaction(immediateRepeat),
                "identical payload within the dedupe window should be ignored");

        // 十分钟后再转同样的金额给同一个人：这是新的一笔，必须能入账。
        LedgerEntry laterTransfer = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 转账成功 ¥0.01 张三 对方已收款",
                2_200_000L
        );
        assertTrue(repository.addTransaction(laterTransfer),
                "a later identical transfer should still be stored");

        LedgerRepositorySnapshot snapshot = repository.snapshot();
        assertEquals(2, snapshot.transactions.size(), "both transfers should be kept");
    }

    private static void stableNotificationEventsAreIdempotentWithoutDroppingNewPayments() throws Exception {
        LedgerRepository repository = LedgerRepository.open(tempDir());
        String text = "微信支付 向 星巴克 支付 12.50元";
        LedgerEntry first = TransactionParser.parse("com.tencent.mm", text, 1_000_000L, null, "event-1");
        LedgerEntry retry = TransactionParser.parse("com.tencent.mm", text, 1_000_000L, null, "event-1");
        LedgerEntry second = TransactionParser.parse("com.tencent.mm", text, 1_001_000L, null, "event-2");

        assertEquals(LedgerRepository.AddResult.ADDED, repository.addTransactionResult(first),
                "first stable event is inserted");
        assertEquals(LedgerRepository.AddResult.DUPLICATE, repository.addTransactionResult(retry),
                "replaying the same event remains idempotent");
        assertEquals(LedgerRepository.AddResult.ADDED, repository.addTransactionResult(second),
                "a distinct event with identical text is a separate transaction");
        assertEquals(2, repository.transactionCount(), "new identical-text event is not lost");
    }

    /**
     * 回归测试：账本超过读取上限时，新增一笔不能把历史记录挤掉。
     *
     * 旧实现走「load(maxEntries) 截断读取 → replaceLedger 整表写回」，
     * 账本超过上限后，每记一笔就会静默丢掉一条历史记录。
     * 现在通知通道改为 addTransaction 增量写入，这里锁定该行为。
     */
    /**
     * 用户截图里的重复：同一笔 ¥55，页面通道记下「大连好利来」，
     * 通知通道晚 20 秒只解析出「未知商户」，两条都进了账本。
     *
     * 判重不能再指望指纹——两条原文不同，指纹必然不同。写入口（唯一落盘点）
     * 必须按「金额 + 方向 + 时间」认出这是同一笔，并且把更全的信息合并进去，
     * 而不是把带商户名的那条丢掉。
     */
    private static void mergesCrossChannelDuplicate() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        long at = 1_760_000_000_000L;

        LedgerEntry page = TransactionParser.fromPage(
                "com.tencent.mm",
                "支付成功 ¥55.00 大连好利来 付款方式 零钱",
                5500L,
                "大连好利来",
                "expense",
                at,
                new java.util.ArrayList<>(),
                ""
        );
        assertEquals(LedgerRepository.AddResult.ADDED, repository.addTransactionResult(page),
                "页面通道先记一笔");

        // 通知通道的同一笔：晚 20 秒、认不出商户、文本完全不同。
        LedgerEntry notification = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 支付成功 ¥55.00",
                at + 20_000L
        );
        assertEquals("未知商户", notification.merchant, "通知里没有商户信息");
        // 通知这条信息量更少（商户更差、原文更短），判定为同一笔并直接丢弃。
        // 关键不是返回值，而是「账本不会变成两条」。
        assertEquals(LedgerRepository.AddResult.DUPLICATE, repository.addTransactionResult(notification),
                "信息更少的同笔记录应被判定为重复，而不是新增");

        LedgerRepositorySnapshot snapshot = repository.snapshot();
        assertEquals(1, snapshot.transactions.size(), "同一笔只应留下一条");
        assertEquals("大连好利来", snapshot.transactions.get(0).merchant, "合并后保留可信的商户名");

        // 反过来：带商户名的后到，也不能把账本变成两条，而且要把商户名补上。
        File secondDir = tempDir();
        LedgerRepository reversed = LedgerRepository.open(secondDir);
        assertEquals(LedgerRepository.AddResult.ADDED, reversed.addTransactionResult(
                TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥55.00", at)
        ), "通知先记一笔");
        assertEquals(LedgerRepository.AddResult.MERGED, reversed.addTransactionResult(page),
                "页面后到应合并");
        assertEquals(1, reversed.transactionCount(), "同一笔只应留下一条");
        assertEquals("大连好利来", reversed.snapshot().transactions.get(0).merchant,
                "后到的商户名要补进原记录");
    }

    /**
     * 已经躺在账本里的重复，要能被一次性清掉。
     *
     * 判重只防「以后」，用户升级完看到的还是那几条重复——必须有一个显式的合并入口，
     * 规则与实时判重完全一致，否则「修好了」只是看起来修好了。
     */
    private static void mergesExistingDuplicatesInLedger() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        long at = 1_760_000_000_000L;

        LedgerEntry page = TransactionParser.fromPage(
                "com.tencent.mm", "支付成功 ¥14.00 红姐包子-软景店", 1400L, "红姐包子-软景店",
                "expense", at, new java.util.ArrayList<>(), ""
        );
        LedgerEntry notification = TransactionParser.parse(
                "com.tencent.mm", "微信支付 支付成功 ¥14.00", at + 5_000L
        );
        // 直接整表写入，绕过入库判重，模拟「历史版本已经记重复」的账本。
        LocalLedger legacy = new LocalLedger(10);
        legacy.add(page);
        legacy.add(notification);
        assertEquals(2, legacy.size(), "两条不同原文都应进内存账本");
        assertTrue(repository.replaceLedger(legacy), "写盘成功");
        assertEquals(2, repository.transactionCount(), "账本里确实有两条重复");

        List<LedgerRepository.DuplicatePair> candidates = repository.findHistoricalDuplicateCandidates();
        assertEquals(1, candidates.size(), "历史相近账单应先成为复核候选");
        assertEquals(2, repository.transactionCount(), "扫描候选不修改历史账本");
        assertTrue(repository.mergeConfirmedDuplicate(candidates.get(0).existing.id, candidates.get(0).candidate.id),
                "只有明确确认后才合并");
        assertEquals(1, repository.transactionCount(), "确认后只剩一条");
        assertEquals("红姐包子-软景店", repository.snapshot().transactions.get(0).merchant,
                "保留带商户名的那条");

        // 同店隔 6 分钟可能是真实复购，只能作为候选，不能自动合并。
        File looseDir = tempDir();
        LedgerRepository loose = LedgerRepository.open(looseDir);
        LocalLedger sixMinutes = new LocalLedger(10);
        sixMinutes.add(TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥14.00 商家：红姐包子", at));
        sixMinutes.add(TransactionParser.parse("com.tencent.mm", "微信支付 向 红姐包子 付款 14.00元", at + 6 * 60_000L));
        assertEquals(2, sixMinutes.size(), "两条应在内存账本里");
        assertTrue(loose.replaceLedger(sixMinutes), "写盘成功");
        assertEquals(1, loose.findHistoricalDuplicateCandidates().size(), "可能复购的历史组合交给用户复核");
        assertEquals(2, loose.transactionCount(), "未确认的历史交易保持两条");
    }

    /**
     * 合并时「主记录排在重复记录之后」的下标越界回归。
     *
     * 账本按时间倒序存放（最新的在最前）。用户确认一对重复时，传入的 existingId
     * 可能是那条更晚、更靠前的记录，duplicateId 反而是更早、更靠后的那条，
     * 于是 existingIndex < duplicateIndex 并不总是成立。旧实现直接
     * set(existingIndex, merged) + remove(duplicateIndex)，当 existingIndex > duplicateIndex
     * 时 remove 让列表前移，合并结果会覆盖到一条完全无关的记录上。
     */
    private static void mergeConfirmedDuplicateHandlesReversedIndexes() throws Exception {
        long at = 1_760_000_000_000L;
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        // 三条记录，时间递增；账本内部按时间倒序存放，所以 existingId 对应最晚那条（下标 0）。
        LedgerEntry older = TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥14.00", at);
        LedgerEntry middle = TransactionParser.parse("com.tencent.mm", "微信支付 向 星巴克 付款 32.00元", at + 60_000L);
        LedgerEntry newer = TransactionParser.fromPage(
                "com.tencent.mm", "支付成功 ¥14.00 红姐包子-软景店", 1400L, "红姐包子-软景店",
                "expense", at + 120_000L, new java.util.ArrayList<>(), ""
        );
        LocalLedger legacy = new LocalLedger(10);
        legacy.add(older);
        legacy.add(middle);
        legacy.add(newer);
        assertTrue(repository.replaceLedger(legacy), "写盘成功");
        assertEquals(3, repository.transactionCount(), "三条记录都在账本里");

        // newer（下标 0）与 older（下标 2）是同一笔：existingIndex=0 < duplicateIndex=2。
        // 更关键的相反情形由下面的翻转调用覆盖：existing 在下标更靠后的位置。
        // 这里故意让 existingId 指向靠后的那条，验证「删除两条再按位插回」不会错位。
        assertTrue(repository.mergeConfirmedDuplicate(newer.id, older.id),
                "主记录在前、重复在后时应能合并");

        LedgerRepositorySnapshot after = repository.snapshot();
        assertEquals(2, after.transactions.size(), "合并后剩两条");
        // 无关的「星巴克 32」这条必须原样保留，不能因为下标错位被覆盖或删除。
        boolean starbucksKept = false;
        for (LedgerEntry entry : after.transactions) {
            if ("星巴克".equals(entry.merchant) && entry.amountCents == 3200L) starbucksKept = true;
        }
        assertTrue(starbucksKept, "无关的中间记录必须原样保留");

        // 反过来：主记录（existing）排在被删的重复记录之后。
        File reversedDir = tempDir();
        LedgerRepository reversed = LedgerRepository.open(reversedDir);
        LedgerEntry earlySparse = TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥14.00", at);
        LedgerEntry lateDetailed = TransactionParser.fromPage(
                "com.tencent.mm", "支付成功 ¥14.00 红姐包子-软景店", 1400L, "红姐包子-软景店",
                "expense", at + 120_000L, new java.util.ArrayList<>(), ""
        );
        LedgerEntry unrelated = TransactionParser.parse("com.tencent.mm", "微信支付 向 星巴克 付款 32.00元", at + 240_000L);
        LocalLedger legacyReversed = new LocalLedger(10);
        legacyReversed.add(earlySparse);
        legacyReversed.add(lateDetailed);
        legacyReversed.add(unrelated);
        assertTrue(reversed.replaceLedger(legacyReversed), "写盘成功");

        // unrelated 最晚、下标 0；lateDetailed 下标 1（existing），earlySparse 下标 2（duplicate）。
        // existingIndex=1 > duplicateIndex=2 成立，正是旧实现会越界的场景。
        assertTrue(reversed.mergeConfirmedDuplicate(lateDetailed.id, earlySparse.id),
                "主记录排在重复记录之后时应能正确合并");
        LedgerRepositorySnapshot reversedAfter = reversed.snapshot();
        assertEquals(2, reversedAfter.transactions.size(), "合并后剩两条");
        boolean reversedStarbucksKept = false;
        boolean mergedMerchantKept = false;
        for (LedgerEntry entry : reversedAfter.transactions) {
            if ("星巴克".equals(entry.merchant) && entry.amountCents == 3200L) reversedStarbucksKept = true;
            if ("红姐包子-软景店".equals(entry.merchant) && entry.amountCents == 1400L) mergedMerchantKept = true;
        }
        assertTrue(reversedStarbucksKept, "无关记录不能被下标错位覆盖");
        assertTrue(mergedMerchantKept, "合并结果应保留可信商户名");
    }

    private static void preservesSameMinuteDifferentMerchantsDuringCleanup() throws Exception {
        LedgerRepository repository = LedgerRepository.open(tempDir());
        long at = 1_760_000_000_000L;
        LocalLedger entries = new LocalLedger(10);
        entries.add(TransactionParser.parse("com.tencent.mm", "微信支付 向 瑞幸咖啡 付款 14.00元", at));
        entries.add(TransactionParser.parse("com.tencent.mm", "微信支付 向 星巴克 付款 14.00元", at + 5_000L));
        assertTrue(repository.replaceLedger(entries), "写盘成功");

        assertEquals(0, repository.findHistoricalDuplicateCandidates().size(), "不同明确商户不应列为相似候选");
        assertEquals(2, repository.transactionCount(), "两笔真实消费都必须保留");
    }

    private static void historicalDuplicateCandidatesRequireConfirmation() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        long at = 1_760_000_000_000L;
        LedgerEntry detailed = TransactionParser.fromPage(
                "com.tencent.mm", "支付成功 ¥14.00 红姐包子-软景店", 1400L, "红姐包子-软景店",
                "expense", at, new java.util.ArrayList<>(), "早餐"
        );
        LedgerEntry sparse = TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥14.00", at + 5_000L);
        LocalLedger legacy = new LocalLedger(10);
        assertTrue(legacy.add(detailed), "历史详细记录应加入测试账本");
        assertTrue(legacy.add(sparse), "历史简略记录应加入测试账本");
        assertTrue(repository.replaceLedger(legacy), "写入模拟的历史账本");

        List<LedgerRepository.DuplicatePair> candidates = repository.findHistoricalDuplicateCandidates();
        assertEquals(1, candidates.size(), "相近的同额历史账单应成为复核候选");
        assertEquals(2, repository.transactionCount(), "扫描不能自动修改历史账本");
        assertEquals(detailed.id, candidates.get(0).existing.id, "候选保留较早记录作为主记录");
        assertEquals(0, repository.findHistoricalDuplicateCandidates(
                java.util.Collections.singleton(candidates.get(0).reviewKey())).size(),
                "已选择保留两笔的组合不应每次重复提示");
        assertTrue(repository.mergeConfirmedDuplicate(candidates.get(0).existing.id, candidates.get(0).candidate.id),
                "用户确认后应能合并候选");
        assertEquals(1, repository.transactionCount(), "确认后只保留一条账单");
        assertEquals("红姐包子-软景店", repository.snapshot().transactions.get(0).merchant,
                "合并后保留更完整的商户信息");

        File ambiguousDir = tempDir();
        LedgerRepository ambiguous = LedgerRepository.open(ambiguousDir);
        LocalLedger uncertain = new LocalLedger(10);
        uncertain.add(TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥8.00", at));
        uncertain.add(TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥8.00", at + 3 * 60_000L));
        assertTrue(ambiguous.replaceLedger(uncertain), "写入两条可能是复购的历史记录");
        assertEquals(1, ambiguous.findHistoricalDuplicateCandidates().size(), "不确定组合列为逐笔候选");
        assertEquals(2, ambiguous.transactionCount(), "未确认的候选必须保留两条");

        LedgerRepository stale = LedgerRepository.open(tempDir());
        LocalLedger staleLedger = LocalLedger.fromEntries(java.util.Arrays.asList(
                TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥8.00", at),
                TransactionParser.parse("com.tencent.mm", "微信支付 支付成功 ¥8.00", at + 5_000L)
        ), 10);
        assertTrue(stale.replaceLedger(staleLedger), "写入待复核账单");
        LedgerRepository.DuplicatePair stalePair = stale.findHistoricalDuplicateCandidates().get(0);
        LedgerEntry edited = stalePair.candidate.withEdits(
                stalePair.candidate.amountCents + 100L,
                stalePair.candidate.direction,
                stalePair.candidate.merchant,
                stalePair.candidate.account,
                stalePair.candidate.category,
                stalePair.candidate.note,
                stalePair.candidate.occurredAtMillis
        );
        assertTrue(stale.updateTransaction(edited), "模拟用户在复核前编辑账单");
        assertFalse(stale.mergeConfirmedDuplicate(stalePair.existing.id, stalePair.candidate.id),
                "候选变更后应拒绝按旧提示合并");
        assertEquals(2, stale.transactionCount(), "过期候选不能误删修改后的账单");
    }

    /**
     * 人工录入的两笔同额同店流水必须各自留下。
     *
     * 自动判重是为「机器可能把同一笔看两遍」设计的；人工录入是用户明确要记一笔。
     * 之前手动记账也走同一条判重，用户在同一家店连买两杯同样价钱的咖啡、手动记
     * 两次，第二次会被并进第一条，界面还弹「保存失败，请重试」——钱被吞了。
     */
    private static void keepsIdenticalManualEntries() throws Exception {
        long at = 1_760_000_000_000L;
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        assertEquals(LedgerRepository.AddResult.ADDED,
                repository.addExplicitTransactionResult(manualEntry("manual-a", at)),
                "手动记账第一笔");
        assertEquals(LedgerRepository.AddResult.ADDED,
                repository.addExplicitTransactionResult(manualEntry("manual-b", at + 30_000L)),
                "手动记账第二笔：同额同店也必须是新增");
        assertEquals(2, repository.transactionCount(), "两笔手动记账都要留下");

        // 批量入库（CSV 导入）同一口径：历史账本按原样进来，不替用户改账。
        File batchDir = tempDir();
        LedgerRepository batch = LedgerRepository.open(batchDir);
        int added = batch.addTransactions(java.util.Arrays.asList(
                manualEntry("csv-a", at),
                manualEntry("csv-b", at + 30_000L)
        ));
        assertEquals(2, added, "同额同店的两条 CSV 记录都应导入");
        assertEquals(2, batch.transactionCount(), "CSV 导入不做同笔合并");

        // 对照：自动采集通道仍然要合并同一笔，否则重复记账就回来了。
        File autoDir = tempDir();
        LedgerRepository auto = LedgerRepository.open(autoDir);
        assertEquals(LedgerRepository.AddResult.ADDED,
                auto.addTransactionResult(autoEntry(at)), "自动通道第一笔");
        LedgerEntry samePaymentFromNotification = new LedgerEntry(
                "auto-notification-" + at, 2800L, "expense", "瑞幸咖啡", "零钱", "餐饮",
                at + 15_000L, "微信支付 支付成功 ¥28.00 瑞幸咖啡", "com.tencent.mm", "notification",
                LedgerCodec.fingerprint("notification-" + at), at, at, 0L, true, ""
        );
        assertTrue(auto.addTransactionResult(samePaymentFromNotification) != LedgerRepository.AddResult.ADDED,
                "不同自动采集通道确认是同一笔时仍应合并");
        assertEquals(1, auto.transactionCount(), "跨通道同一笔只留一条");

        assertEquals(1, repository.findHistoricalDuplicateCandidates().size(), "人工流水只作为需用户确认的候选");
        assertEquals(2, repository.transactionCount(), "两条人工录入未确认前始终保留");
    }

    private static LedgerEntry manualEntry(String id, long occurredAtMillis) {
        return new LedgerEntry(
                id, 2800L, "expense", "瑞幸咖啡", "现金", "餐饮",
                occurredAtMillis, "手动记账 · 瑞幸咖啡", "manual", "manual",
                LedgerCodec.fingerprint(id), occurredAtMillis, occurredAtMillis, 0L, true, "咖啡"
        );
    }

    private static LedgerEntry autoEntry(long occurredAtMillis) {
        String id = "auto-" + occurredAtMillis;
        return new LedgerEntry(
                id, 2800L, "expense", "瑞幸咖啡", "零钱", "餐饮",
                occurredAtMillis, "微信支付 支付成功 ¥28.00 瑞幸咖啡", "com.tencent.mm", "page",
                LedgerCodec.fingerprint(id), occurredAtMillis, occurredAtMillis, 0L, true, ""
        );
    }

    private static void explicitlyReviewedCandidateCanMergeOrRemainDistinct() throws Exception {
        LedgerRepository repository = LedgerRepository.open(tempDir());
        long at = 1_760_000_000_000L;
        LedgerEntry original = new LedgerEntry(
                "review-original", 1400L, "expense", "未知商户", "未识别账户", "其他", at,
                "支付成功 14.00", "com.tencent.mm", "notification", "fp-original", at, at, 0L, true, ""
        );
        LedgerEntry candidate = new LedgerEntry(
                "review-candidate", 1400L, "expense", "瑞幸咖啡", "微信", "餐饮", at + 90_000L,
                "支付成功 瑞幸咖啡 14.00 商品说明 早餐", "com.tencent.mm", "accessibility", "fp-candidate", at, at, 0L, true, "早餐"
        );
        assertEquals(LedgerRepository.AddResult.ADDED, repository.addTransactionResult(original), "原账单入账");
        assertEquals(LedgerRepository.AddResult.MERGED,
                repository.mergeCaptureIntoExisting(original.id, candidate), "用户确认重复后合并候选信息");
        assertEquals(1, repository.transactionCount(), "确认重复只保留一笔账");
        assertEquals("瑞幸咖啡", repository.snapshot().transactions.get(0).merchant, "候选商户信息合入");
        assertEquals("早餐", repository.snapshot().transactions.get(0).note, "候选用途信息合入");

        LedgerEntry kept = new LedgerEntry(
                "review-kept", 1400L, "expense", "瑞幸咖啡", "微信", "餐饮", at + 90_000L,
                candidate.rawText, candidate.sourcePackage, candidate.sourceType, original.fingerprint,
                at, at, 0L, true, "早餐"
        );
        assertEquals(LedgerRepository.AddResult.ADDED, repository.addExplicitTransactionResult(kept),
                "用户明确选择保留两笔时，不应被自动指纹去重拦截");
        assertEquals(2, repository.transactionCount(), "用户选择保留后两笔都在账本里");
    }

    private static void incrementalWriteKeepsHistory() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        int history = 5;
        for (int index = 1; index <= history; index += 1) {
            repository.addTransaction(TransactionParser.parse(
                    "com.tencent.mm",
                    "微信支付 已向 商户" + index + " 支付 " + index + ".00元",
                    index * 100_000L
            ));
        }
        assertEquals(history, repository.snapshot().transactions.size(), "history should be stored");

        // 模拟「读取被截断」：只读 3 条，但写入必须仍然走增量。
        LocalLedger truncated = repository.loadLedger(3);
        assertEquals(3, truncated.size(), "truncated read should return only the limit");

        assertTrue(repository.addTransaction(TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 已向 新商户 支付 9.00元",
                900_000L
        )), "new payment should be appended");
        assertEquals(history + 1, repository.snapshot().transactions.size(),
                "incremental write must not drop earlier history");
    }

    /**
     * 回归测试：写盘失败不能被当成「重复」。
     *
     * 旧实现 add 只返回 boolean，重复和写失败都是 false，
     * 调用方只能当成「这条已经记过了」，磁盘满 / 无权限时用户完全无感。
     */
    private static void writeFailureIsNotReportedAsDuplicate() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        LedgerEntry entry = TransactionParser.parse("com.tencent.mm", "微信支付 向 瑞幸咖啡 支付 ¥18.00", 7000L);
        assertEquals(LedgerRepository.AddResult.ADDED, repository.addTransactionResult(entry),
                "first insert should be added");
        assertEquals(LedgerRepository.AddResult.DUPLICATE, repository.addTransactionResult(entry),
                "same entry should be reported as duplicate");
        assertFalse(repository.addTransaction(entry), "boolean shim should still be false for duplicates");

        // 父路径是普通文件时 mkdirs / 建文件必然失败，用它模拟写盘失败。
        File blocker = new File(dir, "blocker");
        Files.write(blocker.toPath(), new byte[]{1});
        LedgerRepository broken = LedgerRepository.open(new File(blocker, "storage"));
        assertEquals(LedgerRepository.AddResult.FAILED,
                broken.addTransactionResult(TransactionParser.parse(
                        "com.tencent.mm", "微信支付 向 瑞幸咖啡 支付 ¥19.00", 8000L)),
                "a failed write must not be reported as duplicate");
    }

    /**
     * 状态缓存 + 备份恢复的一致性。
     *
     * 缓存是为了避免设置页每次刷新都整份重解析（8000 条时 30ms+）；
     * 但它必须做到：别的实例写进来的数据能看见、文件被删了不能继续吐旧数据。
     */
    private static void cacheAndRecoveryStayConsistent() throws Exception {
        File dir = tempDir();
        LedgerRepository writer = LedgerRepository.open(dir);
        LedgerRepository reader = LedgerRepository.open(dir);

        assertEquals(0, reader.snapshot().transactions.size(), "reader should start empty");
        assertTrue(writer.addTransaction(TransactionParser.parse(
                "com.tencent.mm", "微信支付 向 瑞幸咖啡 支付 ¥18.00", 100_000L)), "write through writer");
        assertEquals(1, reader.snapshot().transactions.size(),
                "另一个实例写入后，读实例必须能看见（缓存要失效）");
        assertEquals(1, writer.snapshot().transactions.size(), "writer should see its own record");

        // 主文件被删掉：不能继续返回缓存，而应当从备份恢复。
        File main = new File(dir, "ledger-store-v2.db");
        assertTrue(main.delete(), "main file should be deletable for this test");
        assertEquals(1, reader.snapshot().transactions.size(),
                "主文件丢失时应从备份恢复，而不是返回空账本");
        assertTrue(main.exists(), "recovery should rewrite the main file");

        // 主文件被截断成 0 字节：同样要从备份恢复。
        Files.write(main.toPath(), new byte[0]);
        assertEquals(1, reader.snapshot().transactions.size(),
                "主文件被截断为 0 字节时应从备份恢复");

        // 真正清空之后，不能再被备份"复活"。
        writer.clearAll();
        assertEquals(0, reader.snapshot().transactions.size(), "clearAll 之后不能被备份复活");
        assertTrue(main.delete(), "main file should be deletable again");
        assertEquals(0, reader.snapshot().transactions.size(), "cleared ledger stays empty");
    }

    /**
     * 回归测试：统计类查询不能被「读取上限」截断。
     *
     * 首页的「本月支出 / 账单数」和统计页原来都基于 loadLedger(MAX_LOCAL_ENTRIES)，
     * 账本超过上限后这些数字会越算越小，而用户完全看不出来。
     */
    private static void aggregatesIgnoreReadLimit() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        for (int index = 1; index <= 5; index += 1) {
            assertTrue(repository.addTransaction(TransactionParser.parse(
                    "com.tencent.mm",
                    "微信支付 已向 商户" + index + " 支付 " + index + ".00元",
                    index * 100_000L
            )), "seed transaction " + index);
        }

        assertEquals(5, repository.transactionCount(), "count must cover the whole ledger");
        assertTrue(repository.loadLedger(3).size() == 3, "truncated read still truncates");
        assertEquals(1500L, repository.expenseTotalSince(0L), "expense total must cover the whole ledger");
        assertEquals(3, repository.entriesSince(300_000L).size(), "entriesSince must filter on the whole ledger");
    }

    /** 用途要能落盘、能读回——它是「这笔钱花在哪」的唯一载体。 */
    private static void purposeSurvivesRoundTrip() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        LedgerEntry entry = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 你已支付 ¥28.00",
                1_000_000L
        ).withAmountPurpose(2800L, "瑞幸咖啡", "微信", "餐饮", "和同事聊项目");
        assertTrue(repository.addTransaction(entry), "entry with purpose is stored");

        LedgerRepository reopened = LedgerRepository.open(dir);
        List<LedgerEntry> entries = reopened.entriesSince(0L);
        assertEquals(1, entries.size(), "one stored entry");
        assertEquals("和同事聊项目", entries.get(0).note, "purpose survives a restart");
        assertEquals("餐饮", entries.get(0).category, "category survives a restart");
        assertFalse(entries.get(0).needsPurpose(), "entry with a purpose is not pending");
    }

    /**
     * 老数据是 15 列，没有用途字段。
     * 加字段时如果解析不够宽容，升级 app 就等于把用户历史账单全弄坏。
     */
    private static void legacyRowsWithoutPurposeStillLoad() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        assertTrue(repository.addTransaction(TransactionParser.parse(
                "com.tencent.mm", "微信支付 你已支付 ¥12.00", 2_000_000L)), "seed entry");

        File main = new File(dir, "ledger-store-v2.db");
        String raw = new String(Files.readAllBytes(main.toPath()), StandardCharsets.UTF_8);
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String rewritten = line;
            if (line.startsWith("ROW\t")) {
                String[] fields = line.split("\t", -1);
                // 只有交易行是 16 列；其它 section 的列数不同，按列数区分不会误伤。
                if (fields.length == 16) {
                    StringBuilder joined = new StringBuilder();
                    for (int index = 0; index < 15; index += 1) {
                        if (index > 0) joined.append('\t');
                        joined.append(fields[index]);
                    }
                    rewritten = joined.toString();
                }
            }
            stripped.append(rewritten).append('\n');
        }
        Files.write(main.toPath(), stripped.toString().getBytes(StandardCharsets.UTF_8));

        LedgerRepository reopened = LedgerRepository.open(dir);
        List<LedgerEntry> entries = reopened.entriesSince(0L);
        assertEquals(1, entries.size(), "legacy row still parses");
        assertEquals(1200L, entries.get(0).amountCents, "legacy amount intact");
        assertEquals("", entries.get(0).note, "legacy row has an empty purpose");
        assertTrue(entries.get(0).needsPurpose(), "legacy row shows up in the to-do list");
    }

    /**
     * 损坏的交易行不能被当成一条「空账单」塞进账本。
     *
     * 截断的文件里常见这种行：只有 ROW 前缀，后面全是空字段。
     * 以前它会解出一条 id 为空、金额为 0 的记录，列表里显示成「未知商户 ¥0.00」，
     * 而且因为 id 是空的，点击、删除都对不上号。
     */
    private static void corruptTransactionRowIsDropped() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        assertTrue(repository.addTransaction(TransactionParser.parse(
                "com.tencent.mm", "微信支付 你已支付 ¥12.00", 2_000_000L)), "seed entry");

        File main = new File(dir, "ledger-store-v2.db");
        String raw = new String(Files.readAllBytes(main.toPath()), StandardCharsets.UTF_8);
        StringBuilder corrupt = new StringBuilder("ROW");
        for (int index = 0; index < 16; index += 1) corrupt.append('\t');
        Files.write(main.toPath(), (raw + corrupt + "\n").getBytes(StandardCharsets.UTF_8));

        LedgerRepository reopened = LedgerRepository.open(dir);
        List<LedgerEntry> entries = reopened.entriesSince(0L);
        assertEquals(1, entries.size(), "corrupt row must not become an empty entry");
        assertEquals(1200L, entries.get(0).amountCents, "the valid entry still loads");
        assertEquals(1, reopened.transactionCount(), "count ignores the corrupt row");
    }

    /** 学到的规则要覆盖同一个商户，而不是每次叠加一条。 */
    private static void learnsMerchantCategory() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        assertTrue(repository.learnMerchantCategory("瑞幸咖啡(国贸店)", "餐饮"), "learns a rule");
        assertEquals(1, repository.loadRules().size(), "one rule stored");
        assertEquals("瑞幸咖啡\t餐饮", repository.loadRules().get(0), "merchant key strips the branch name");

        // 换一家门店、改成另一个分类：同一个商户只应保留一条规则。
        assertTrue(repository.learnMerchantCategory("瑞幸咖啡(望京店)", "零食"), "updates the rule");
        assertEquals(1, repository.loadRules().size(), "same merchant does not pile up rules");
        assertEquals("瑞幸咖啡\t零食", repository.loadRules().get(0), "latest choice wins");

        // 「其他」不是一个分类，是「还不知道」，学它只会把所有东西归到其他。
        assertFalse(repository.learnMerchantCategory("星巴克", "其他"), "other is not learnable");
        assertFalse(repository.learnMerchantCategory("未知商户", "餐饮"), "unknown merchant is not learnable");
        assertFalse(repository.learnMerchantCategory("", "餐饮"), "empty merchant is not learnable");
        assertEquals(1, repository.loadRules().size(), "rejected learns leave rules untouched");

        // 学到的规则要真的能让下一笔自动归位。
        LedgerEntry next = TransactionParser.parse(
                "com.tencent.mm",
                "微信支付 瑞幸咖啡(国贸店) ¥19.90",
                3_000_000L,
                repository.loadRules()
        );
        assertEquals("零食", next.category, "learned rule applies to the next payment of that merchant");
    }

    /** 平台商户下按商品名学习：只记「螺蛳粉→餐饮」，不污染「美团」这个商户名。 */
    private static void learnsProductCategory() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        assertTrue(repository.learnProductCategory("螺蛳粉", "餐饮"), "learns a product rule");
        assertEquals(1, repository.loadRules().size(), "one rule stored");
        assertEquals("螺蛳粉\t餐饮", repository.loadRules().get(0), "product keyword stored as-is");

        // 换一个商品：商品规则各自独立，互不覆盖。
        assertTrue(repository.learnProductCategory("感冒灵", "医疗"), "learns a second product rule");
        assertEquals(2, repository.loadRules().size(), "different products keep their own rules");

        // 泛词/占位值不该被学进去。
        assertFalse(repository.learnProductCategory("其他", "餐饮"), "placeholder is not learnable");
        assertFalse(repository.learnProductCategory("", "餐饮"), "empty product is not learnable");
        assertFalse(repository.learnProductCategory("这个商品名字特别特别长超过八字", "餐饮"), "oversized product is not learnable");
        assertEquals(2, repository.loadRules().size(), "rejected learns leave rules untouched");

        // 学到的商品规则要真的能让「美团 + 螺蛳粉」自动归餐饮，而不是落到平台名的餐饮。
        LedgerEntry next = TransactionParser.fromPage(
                "com.tencent.mm",
                "支付成功 ¥25.00",
                2500, "美团外卖", "expense", 0L, repository.loadRules(), "螺蛳粉"
        );
        assertEquals("餐饮", next.category, "learned product rule applies via note");
    }

    /** 原子追加/删除：追加不覆盖已有、同指纹去重、删除只删一条。 */
    private static void atomicPendingCaptureAppendAndRemove() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        assertTrue(repository.addPendingCapture("支付宝 向 星巴克 付款 32.00元", "event-1"), "first append succeeds");
        assertTrue(repository.addPendingCapture("微信 支付 46.80元", "event-2"), "second append succeeds");
        assertFalse(repository.addPendingCapture("支付宝 向 星巴克 付款 32.00元", "event-1"),
                "retrying the same event ID is deduped");
        assertTrue(repository.addPendingCapture("支付宝 向 星巴克 付款 32.00元", "event-3"),
                "identical text from a distinct event remains queued");

        PendingCaptureQueue queue = repository.loadPendingQueue(10);
        assertEquals(3, queue.size(), "same text outside the retry window remains a distinct capture");

        // 删除一条：只删这一条，另一条不受影响。
        assertTrue(repository.removePendingCapture("支付宝 向 星巴克 付款 32.00元"), "remove existing succeeds");
        PendingCaptureQueue afterRemove = repository.loadPendingQueue(10);
        assertEquals(2, afterRemove.size(), "one matching capture removed");
        assertTrue(afterRemove.items().stream().anyMatch(item -> item.contains("46.80")), "the other capture survives");

        // 删不存在的：返回 false，不动队列。
        assertFalse(repository.removePendingCapture("不存在的记录"), "removing absent capture returns false");
        assertEquals(2, repository.loadPendingQueue(10).size(), "absent removal leaves queue intact");
    }

    private static void pendingDuplicateCaptureSurvivesHistoryPruning() throws Exception {
        LedgerRepository repository = LedgerRepository.open(tempDir());
        CaptureEvent candidate = new CaptureEvent(
                1_760_000_000_000L, "possible_duplicate", CaptureHistory.possibleDuplicateReason("entry-1"),
                "com.tencent.mm", "微信支付 ¥18.00");
        repository.addCaptureEvent(candidate);
        for (int index = 0; index < 40; index += 1) {
            repository.addCaptureEvent(new CaptureEvent(
                    candidate.timestampMillis + index + 1, "captured", "captured",
                    "com.tencent.mm", "普通记录 " + index));
        }
        List<CaptureEvent> history = repository.loadCaptureHistory(30).entries();
        assertTrue(containsPendingCapture(candidate, history), "待复核事件不能被普通日志挤出历史上限");

        repository.addCaptureEvent(new CaptureEvent(
                candidate.timestampMillis + 100, "resolved",
                CaptureHistory.resolutionReason(candidate, "candidate_kept"),
                candidate.packageName, candidate.text));
        history = repository.loadCaptureHistory(30).entries();
        assertFalse(containsPendingCapture(candidate, history), "已处理事件可以从待复核队列中移出");
    }

    private static boolean containsPendingCapture(CaptureEvent candidate, List<CaptureEvent> events) {
        for (CaptureEvent event : events) {
            if (candidate.reason.equals(event.reason) && candidate.timestampMillis == event.timestampMillis
                    && CaptureHistory.isPendingDuplicate(event, events)) return true;
        }
        return false;
    }

    /**
     * 教一次，历史同商户的「其他」应该一起改掉。
     * 只动「其他/未分类」，用户明确改过的分类不能被规则覆盖。
     */
    private static void backfillsHistoryWhenCategoryIsLearned() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);

        // 这三笔先强制停在「其他」，模拟还没归类时的状态。
        repository.addTransaction(historyEntry("瑞幸咖啡(国贸店)", "其他", 1_000_000L));
        repository.addTransaction(historyEntry("瑞幸咖啡(望京店)", "其他", 2_000_000L));
        // 这一笔已经被用户归成「餐饮」，不该被后来的规则改写。
        repository.addTransaction(historyEntry("瑞幸咖啡(三里屯店)", "餐饮", 3_000_000L));

        int changed = repository.backfillCategory("瑞幸咖啡", "零食");
        assertEquals(2, changed, "只回填两笔「其他」，不动已归类的记录");
        assertEquals(0, repository.backfillCategory("瑞幸咖啡", "零食"), "再跑一次没有可改的");
        assertEquals(0, repository.backfillCategory("未知商户", "零食"), "商户识别不出来时不回填");
        assertEquals(0, repository.backfillCategory("瑞幸咖啡", "其他"), "「其他」不是可学分类");

        int snack = 0;
        int meal = 0;
        for (LedgerEntry entry : repository.snapshot().transactions) {
            if ("零食".equals(entry.category)) snack += 1;
            if ("餐饮".equals(entry.category)) meal += 1;
        }
        assertEquals(2, snack, "两笔历史被改成新分类");
        assertEquals(1, meal, "用户自己归过的那笔保持不变");
    }

    private static LedgerEntry historyEntry(String merchant, String category, long time) {
        return new LedgerEntry(
                "history-" + time,
                1990L,
                "expense",
                merchant,
                "微信",
                category,
                time,
                merchant + " 消费 19.90元"
        );
    }

    /** 编辑一次不能把来源信息、指纹弄丢——那会让跨通道去重失效。 */
    private static void editingKeepsSourceAndPurpose() throws Exception {
        File dir = tempDir();
        LedgerRepository repository = LedgerRepository.open(dir);
        LedgerEntry original = TransactionParser.parse(
                "com.tencent.mm", "微信支付 你已支付 ¥46.80", 4_000_000L);
        assertTrue(repository.addTransaction(original), "seed entry");

        LedgerEntry edited = original.withAmountPurpose(4680L, "美团外卖", "微信", "餐饮", "午饭");
        assertTrue(repository.updateTransaction(edited), "update succeeds");

        LedgerEntry stored = repository.entriesSince(0L).get(0);
        assertEquals("午饭", stored.note, "purpose is saved");
        assertEquals("com.tencent.mm", stored.sourcePackage, "source package is preserved");
        assertEquals("notification", stored.sourceType, "source type is preserved");
        assertFalse(stored.fingerprint.isEmpty(), "fingerprint is preserved");
    }

    private static File tempDir() throws IOException {
        File dir = Files.createTempDirectory("ziji-ledger-test").toFile();
        dir.deleteOnExit();
        return dir;
    }

    private static void assertTrue(boolean value, String message) {
        checks += 1;
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        checks += 1;
        if (value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        checks += 1;
        if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
