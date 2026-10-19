package com.ziji.ledger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

public final class LedgerRepository {
    private static final String MAIN_FILE = "ledger-store-v2.db";
    private static final String BACKUP_FILE = "ledger-store-v2.db.bak";
    private static final String CORRUPT_PREFIX = "ledger-store-v2.db.corrupt-";
    private static final String HEADER = "ZIJI_LEDGER_DB\t1";
    private static final Object FILE_LOCK = new Object();
    /** 同指纹去重时间窗：只有短时间内完全相同的指纹才视为同一次通知的重复投递。 */
    private static final long FINGERPRINT_DEDUPE_WINDOW_MILLIS = 2 * 60 * 1000L;
    /** 学到的「商户 → 分类」规则上限，防止无限制增长拖慢每次解析。 */
    private static final int MAX_LEARNED_RULES = 600;

    private final File storageDir;
    private final File mainFile;
    private final File backupFile;

    /**
     * 进程内状态缓存。
     *
     * 每次 snapshot() 都要把整份文件读出来解析一遍：实测 8000 条时要 30~36ms，
     * 而设置页在 onResume / 切页签时都会读一次，全部落在主线程上。
     * 这里缓存解析结果，用「主文件路径 + 修改时间 + 长度」三个条件校验，
     * 文件被外部改动（或写入失败）时自动失效重读。
     *
     * 只能在 {@link #FILE_LOCK} 内访问。
     */
    private static String cacheKey;
    private static long cacheMtime;
    private static long cacheLength;
    private static LedgerState cacheState;

    public LedgerRepository(File storageDir) {
        this.storageDir = storageDir;
        this.mainFile = new File(storageDir, MAIN_FILE);
        this.backupFile = new File(storageDir, BACKUP_FILE);
    }

    public static LedgerRepository open(File storageDir) {
        return new LedgerRepository(storageDir);
    }

    public LedgerRepositorySnapshot snapshot() {
        synchronized (FILE_LOCK) {
            return readState().toSnapshot();
        }
    }

    public String exportBackup() {
        synchronized (FILE_LOCK) {
            return serialize(readState());
        }
    }

    /**
     * 从备份内容恢复整份账本。
     *
     * 空内容必须当成「这不是备份文件」，而不是「恢复成空账本」：
     * 以前 {@code parseState("")} 返回的是一个空状态（不是 null），这里就
     * 一路写到磁盘并返回 true，界面提示「账本已恢复」——用户选错一个 0 字节的
     * 文件，账本和 .bak 会同时被清空，连回滚的机会都没有。
     */
    public boolean importBackup(String raw) {
        synchronized (FILE_LOCK) {
            if (raw == null || raw.trim().isEmpty()) return false;
            LedgerState state = parseState(raw);
            if (state == null) return false;
            // 格式对但没有任何内容（只剩一行表头）：同样不是一份可恢复的备份。
            if (!state.hasContent()) return false;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    public List<String> loadRules() {
        return snapshot().rules;
    }

    public boolean replaceRules(List<String> rules) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            state.rules = new ArrayList<>(rules == null ? new ArrayList<>() : rules);
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    /**
     * 记住「这个商户属于哪一类」。
     *
     * 自动采集拿不到分类时只能落到「其他」，用户手动改一次之后，
     * 下一次同一个商户就应该自动归位——否则每次都要手动点，等于没做。
     * 规则复用现有的 {@code 关键词\t分类} 格式（解析器已经在读这张表）。
     *
     * 同一个关键词只保留一条：用户改主意时是新规则覆盖旧的，而不是叠加。
     */
    public boolean learnMerchantCategory(String merchant, String category) {
        String keyword = TransactionParser.merchantKey(merchant);
        String target = category == null ? "" : category.trim();
        if (keyword.isEmpty() || target.isEmpty() || "其他".equals(target) || "未分类".equals(target)) {
            return false;
        }
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            String encoded = keyword + "\t" + target;
            List<String> next = new ArrayList<>();
            // 学到的规则放最前面：它比用户早期写下的宽泛关键词更具体，应该优先命中。
            next.add(encoded);
            for (String row : state.rules) {
                if (row == null) continue;
                String[] fields = row.split("\t", 2);
                if (fields.length == 2 && keyword.equalsIgnoreCase(fields[0].trim())) continue;
                next.add(row);
            }
            if (next.size() > MAX_LEARNED_RULES) {
                next = new ArrayList<>(next.subList(0, MAX_LEARNED_RULES));
            }
            state.rules = next;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    /**
     * 记住「这个商品属于哪一类」。
     *
     * 平台型商户（美团/饿了么/京东）背后商品千差万别，光记商户名会把「美团」下一笔
     * 完全不同的商品也归成同一类。商品名（note，即「商品说明/订单标题」抽出的值）才是
     * 真实消费对象——用户在美团点「螺蛳粉」后手动归到餐饮，就记「螺蛳粉 → 餐饮」，
     * 下次同类商品自动归类，而「美团」本身不动，不会污染别的商品。
     *
     * 只学「具体商品词」：非空、长度 2~8 字、不含空白、且不能是「其他/未知」这类占位值。
     */
    public boolean learnProductCategory(String note, String category) {
        String keyword = safe(note).trim();
        String target = category == null ? "" : category.trim();
        if (keyword.isEmpty() || keyword.length() < 2 || keyword.length() > 8) return false;
        if (keyword.contains(" ") || keyword.contains("\t")) return false;
        if ("其他".equals(keyword) || "未知".equals(keyword) || "未分类".equals(keyword)) return false;
        if (target.isEmpty() || "其他".equals(target) || "未分类".equals(target)) return false;
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            String encoded = keyword + "\t" + target;
            List<String> next = new ArrayList<>();
            next.add(encoded);
            for (String row : state.rules) {
                if (row == null) continue;
                String[] fields = row.split("\t", 2);
                if (fields.length == 2 && keyword.equalsIgnoreCase(fields[0].trim())) continue;
                next.add(row);
            }
            if (next.size() > MAX_LEARNED_RULES) {
                next = new ArrayList<>(next.subList(0, MAX_LEARNED_RULES));
            }
            state.rules = next;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    public LocalLedger loadLedger(int maxEntries) {
        return LocalLedger.fromEntries(snapshot().transactions, maxEntries);
    }

    /**
     * 把同商户的「其他」历史记录一并改成刚学到的分类。
     *
     * 只回填分类是「其他 / 未分类」的记录：用户手改过的分类属于明确意图，
     * 不该被一条新规则覆盖。返回实际改动的条数。
     */
    public int backfillCategory(String merchant, String category) {
        String keyword = TransactionParser.merchantKey(merchant);
        String target = category == null ? "" : category.trim();
        if (keyword.isEmpty() || target.isEmpty() || "其他".equals(target) || "未分类".equals(target)) {
            return 0;
        }
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            int changed = 0;
            for (int index = 0; index < state.transactions.size(); index += 1) {
                TransactionRecord record = state.transactions.get(index);
                if (!"其他".equals(record.category) && !"未分类".equals(record.category)) continue;
                if (!keyword.equals(TransactionParser.merchantKey(record.merchant))) continue;
                state.transactions.set(index, record.withCategory(target));
                changed += 1;
            }
            if (changed == 0) return 0;
            state.updatedAtMillis = System.currentTimeMillis();
            // 写盘失败就当作没回填，避免界面报出的数字和磁盘上的不一致。
            return writeState(state) ? changed : 0;
        }
    }

    /**
     * 全量账本里筛选出「某个时间点之后」的记录。
     *
     * 统计和首页概览不能走 {@link #loadLedger(int)}：那个接口按条数截断，
     * 账本超过上限之后，本月支出、账单数都会算少。
     */
    public List<LedgerEntry> entriesSince(long startMillis) {
        synchronized (FILE_LOCK) {
            List<LedgerEntry> result = new ArrayList<>();
            for (TransactionRecord record : readState().transactions) {
                if (record.occurredAtMillis >= startMillis) result.add(record.toLedgerEntry());
            }
            return result;
        }
    }

    /** 左闭右开区间，用于按月查看历史。 */
    public List<LedgerEntry> entriesBetween(long startMillis, long endMillis) {
        synchronized (FILE_LOCK) {
            List<LedgerEntry> result = new ArrayList<>();
            for (TransactionRecord record : readState().transactions) {
                if (record.occurredAtMillis >= startMillis && record.occurredAtMillis < endMillis) {
                    result.add(record.toLedgerEntry());
                }
            }
            return result;
        }
    }

    /**
     * 还没写清用途的记录，最近的排在前面；limit 为 0 表示不限。
     *
     * 这个查询刻意跨月：补用途是「待办」，不是「本月报表」。
     * 之前提示条按本月算、「下一笔」却从截断到 2000 条的视图里取，
     * 两个范围不一致，用户点进去看到的往往不是提示里说的那一笔。
     */
    public List<LedgerEntry> entriesNeedingPurpose(int limit) {
        synchronized (FILE_LOCK) {
            List<LedgerEntry> result = new ArrayList<>();
            for (TransactionRecord record : readState().transactions) {
                LedgerEntry entry = record.toLedgerEntry();
                if (entry.needsPurpose()) result.add(entry);
            }
            result.sort((left, right) -> Long.compare(right.occurredAtMillis, left.occurredAtMillis));
            if (limit > 0 && result.size() > limit) {
                return new ArrayList<>(result.subList(0, limit));
            }
            return result;
        }
    }

    /** 全量账本的总条数（不受读取上限影响）。 */
    public int transactionCount() {
        synchronized (FILE_LOCK) {
            return readState().transactions.size();
        }
    }

    /**
     * 全量账本的用途覆盖：[已知用途条数, 总条数]。
     *
     * 首页以前把「账单数」按全量算、「已知用途」按截断到 2000 条的视图算。
     * 账本超过 2000 条后，会出现「2500 笔账单 / 1800/2000 笔已知用途」这种
     * 分母对不上的展示——用户看到两个互相矛盾的数，比看不到更糟。
     */
    public int[] purposeCoverage() {
        synchronized (FILE_LOCK) {
            int known = 0;
            int total = 0;
            for (TransactionRecord record : readState().transactions) {
                total += 1;
                if (!record.toLedgerEntry().needsPurpose()) known += 1;
            }
            return new int[] {known, total};
        }
    }

    /** 全量账本里某个时间点之后的支出合计（不受读取上限影响）。 */
    public long expenseTotalSince(long startMillis) {
        synchronized (FILE_LOCK) {
            long total = 0L;
            for (TransactionRecord record : readState().transactions) {
                if (record.occurredAtMillis < startMillis) continue;
                if (!"expense".equals(record.direction)) continue;
                total += record.amountCents;
            }
            return total;
        }
    }

    public boolean replaceLedger(LocalLedger ledger) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            state.transactions = new ArrayList<>();
            for (LedgerEntry entry : ledger.entries()) {
                state.transactions.add(TransactionRecord.from(entry));
            }
            state.migrationComplete = state.migrationComplete || !state.legacyChecksum.isEmpty();
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    /**
     * 写入结果。以前 addTransaction 只返回 boolean，
     * 「重复」和「磁盘写失败」都是 false，调用方无法区分，
     * 于是写盘失败会被当成「这条已经记过了」而静默吞掉。
     */
    public enum AddResult {
        /** 新增成功。 */
        ADDED,
        /** 已存在（id 相同，或时间窗内的同指纹重复投递）。 */
        DUPLICATE,
        /** 落盘失败，数据没保存。 */
        FAILED
    }

    public boolean addTransaction(LedgerEntry entry) {
        return addTransactionResult(entry) == AddResult.ADDED;
    }

    public AddResult addTransactionResult(LedgerEntry entry) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            TransactionRecord record = TransactionRecord.from(entry);
            // id 不同即视为不同交易（id 里含时间戳）。
            // 指纹不含时间戳，必须加上时间窗，否则「相同金额+相同商户」的后一笔
            // 支付会被当成重复永久丢弃——这是「后续支付记不上」的关键原因。
            if (state.hasTransactionId(record.id)
                    || state.hasRecentlySameFingerprint(record.fingerprint, record.occurredAtMillis)) {
                return AddResult.DUPLICATE;
            }
            state.transactions.add(0, record);
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state) ? AddResult.ADDED : AddResult.FAILED;
        }
    }

    /**
     * 批量入库，整批只写一次盘。
     *
     * 导入 CSV 时逐条调 addTransaction，会把整本账重写 N 次：读全量 → 序列化 →
     * 临时文件 → rename。一千条就是一千次 fsync，手机上要等十几秒。
     * 这里把 N 次写压成一次。
     *
     * @return 实际新增的条数（已存在的会被跳过）
     */
    public int addTransactions(List<LedgerEntry> batch) {
        if (batch == null || batch.isEmpty()) return 0;
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            int added = 0;
            for (LedgerEntry entry : batch) {
                if (entry == null || entry.amountCents <= 0) continue;
                TransactionRecord record = TransactionRecord.from(entry);
                if (state.hasTransactionId(record.id)
                        || state.hasRecentlySameFingerprint(record.fingerprint, record.occurredAtMillis)) {
                    continue;
                }
                state.transactions.add(0, record);
                added += 1;
            }
            if (added == 0) return 0;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state) ? added : 0;
        }
    }

    public boolean updateTransaction(LedgerEntry entry) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            for (int index = 0; index < state.transactions.size(); index += 1) {
                TransactionRecord current = state.transactions.get(index);
                if (current.id.equals(entry.id)) {
                    state.transactions.set(index, current.updatedFrom(entry));
                    state.updatedAtMillis = System.currentTimeMillis();
                    return writeState(state);
                }
            }
            return false;
        }
    }

    /**
     * 把历史数据里的旧分类名改成新名字（住房→居家、教育→学习）。
     *
     * 早期网格和解析器各有一套名字，同一个概念被写成两个词，统计里就算成两个分类。
     * 这里一次性改写历史记录，之后展示、统计、筛选才只有一套名字。
     *
     * 幂等：没有需要改的记录就不写盘，避免每次启动都产生一次 fsync。
     *
     * @return 实际改写的条数；写盘失败返回 0。
     */
    public int migrateCategoryAliases() {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            int changed = 0;
            for (int index = 0; index < state.transactions.size(); index += 1) {
                TransactionRecord current = state.transactions.get(index);
                String canonical = LedgerCategories.canonical(current.category);
                if (canonical.equals(current.category)) continue;
                TransactionRecord migrated = current.updatedFrom(current.toLedgerEntry().withCategory(canonical));
                state.transactions.set(index, migrated);
                changed += 1;
            }
            if (changed == 0) return 0;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state) ? changed : 0;
        }
    }

    public boolean removeTransaction(String id) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            boolean removed = false;
            for (Iterator<TransactionRecord> iterator = state.transactions.iterator(); iterator.hasNext(); ) {
                if (iterator.next().id.equals(id)) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) return false;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    public CaptureHistory loadCaptureHistory(int maxEntries) {
        return CaptureHistory.fromEntries(snapshot().captureHistory, maxEntries);
    }

    public void addCaptureEvent(CaptureEvent event) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            state.captureHistory.add(0, CaptureRecord.from(event));
            while (state.captureHistory.size() > 30) {
                state.captureHistory.remove(state.captureHistory.size() - 1);
            }
            state.updatedAtMillis = System.currentTimeMillis();
            writeState(state);
        }
    }

    public PendingCaptureQueue loadPendingQueue(int maxEntries) {
        return PendingCaptureQueue.fromItems(snapshot().pendingCaptures, maxEntries);
    }

    public void savePendingQueue(PendingCaptureQueue queue) {
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            List<PendingRecord> next = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (String item : queue.items()) {
                String normalized = normalize(item);
                PendingRecord existing = state.findPendingByFingerprint(PendingRecord.fingerprintOf(normalized));
                next.add(existing == null ? PendingRecord.from(item, now) : existing.withText(item));
            }
            state.pendingCaptures = dedupePending(next);
            state.updatedAtMillis = now;
            writeState(state);
        }
    }

    /**
     * 原子地追加一条待发采集，返回是否真的新增（同指纹已存在则不变）。
     *
     * 采集通道（通知/无障碍/分享）以前都是「load 快照 → add → save 整份」，这中间
     * 没有锁，与 flush 的「load → 发 → save」并发时，flush 手里的旧快照会在 save 时
     * 把采集刚写入的新条目整份覆盖掉——新捕获的那条就丢了。
     *
     * 这里在 FILE_LOCK 内「读最新状态 → 追加 → 写回」，一步到位，杜绝读旧写回。
     */
    public boolean addPendingCapture(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            PendingRecord record = PendingRecord.from(normalized, System.currentTimeMillis());
            if (state.findPendingByFingerprint(record.fingerprint) != null) return false;
            List<PendingRecord> next = new ArrayList<>(state.pendingCaptures);
            next.add(record);
            state.pendingCaptures = dedupePending(next);
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    /**
     * 原子地移除一条待发采集（flush 成功后调用）。
     *
     * flush 以前用 savePendingQueue 整份覆盖来「删掉已发的」，与并发追加互相踩：
     * 旧快照覆盖会把并发新捕获的条目一起抹掉。改成按指纹原子删除，只动这一条。
     */
    public boolean removePendingCapture(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        synchronized (FILE_LOCK) {
            LedgerState state = readState();
            // 指纹要和 PendingRecord.from 里存的一致：都是 fingerprintOf("pending|" + normalized)。
            String fingerprint = PendingRecord.fingerprintOf("pending|" + normalized);
            List<PendingRecord> next = new ArrayList<>();
            boolean removed = false;
            for (PendingRecord record : state.pendingCaptures) {
                if (!removed && fingerprint.equals(record.fingerprint)) {
                    removed = true;
                    continue;
                }
                next.add(record);
            }
            if (!removed) return false;
            state.pendingCaptures = next;
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    public LedgerMigrationReport migrateLegacy(LegacyLedgerSnapshot legacy) {
        synchronized (FILE_LOCK) {
            if (legacy == null || legacy.isEmpty()) {
                return new LedgerMigrationReport(true, false, "", "", 0, 0, 0, 0L, "empty");
            }

            if (isMigrated(legacy.checksum())) {
                return new LedgerMigrationReport(true, false, "", legacy.checksum(), 0, 0, 0, 0L, "already_migrated");
            }

            LegacyLedgerMigrator.Result migrated = LegacyLedgerMigrator.migrate(legacy);
            ensureStorageDir();
            String backupPath = writeLegacyBackup(legacy);

            LedgerState state = LedgerState.fromSnapshot(migrated.snapshot);
            state.migrationComplete = true;
            state.legacyChecksum = legacy.checksum();
            state.migratedAtMillis = System.currentTimeMillis();
            state.updatedAtMillis = state.migratedAtMillis;

            if (!writeState(state)) {
                return new LedgerMigrationReport(false, true, backupPath, legacy.checksum(), 0, 0, 0, 0L, "write_failed");
            }

            return new LedgerMigrationReport(
                    true,
                    true,
                    backupPath,
                    legacy.checksum(),
                    migrated.report.transactionCount,
                    migrated.report.captureHistoryCount,
                    migrated.report.pendingCount,
                    migrated.report.transactionTotalCents,
                    migrated.report.validationMessage
            );
        }
    }

    public boolean isMigrated(String checksum) {
        LedgerRepositorySnapshot snapshot = snapshot();
        return snapshot.migrationComplete && snapshot.legacyChecksum.equals(checksum == null ? "" : checksum);
    }

    public void clearAll() {
        synchronized (FILE_LOCK) {
            LedgerState empty = new LedgerState();
            empty.migrationComplete = true;
            empty.updatedAtMillis = System.currentTimeMillis();
            writeState(empty);
        }
    }

    public boolean replaceAll(LedgerRepositorySnapshot snapshot) {
        synchronized (FILE_LOCK) {
            LedgerState state = LedgerState.fromSnapshot(snapshot);
            state.updatedAtMillis = System.currentTimeMillis();
            return writeState(state);
        }
    }

    private LedgerState readState() {
        ensureStorageDir();
        LedgerState cached = cachedState();
        if (cached != null) return cached;

        String raw = readText(mainFile);
        if (raw != null && !raw.trim().isEmpty()) {
            LedgerState parsed = parseState(raw);
            if (parsed != null) {
                remember(parsed);
                return parsed;
            }
            // 主文件内容损坏：先隔离留证，再走备份恢复。
            quarantine(raw);
        }

        // 主文件缺失、为空或损坏时都尝试用备份恢复。
        // 以前只有「内容损坏」才看备份，主文件被删掉或截断成 0 字节时
        // 会直接返回空账本——备份明明还在，用户却看到账本清空了。
        LedgerState backup = parseState(readText(backupFile));
        if (backup != null && backup.hasContent()) {
            writeText(mainFile, serialize(backup));
            remember(backup);
            return backup;
        }

        LedgerState empty = new LedgerState();
        remember(empty);
        return empty;
    }

    /**
     * 命中缓存时返回一份「外壳副本」：列表是新数组，行对象共享（都是不可变使用）。
     *
     * 直接返回缓存对象本身不行——调用方会往 transactions 里插记录再落盘，
     * 万一落盘失败，缓存里就留下了没存进磁盘的假数据。
     */
    private LedgerState cachedState() {
        if (cacheState == null) return null;
        if (!mainFile.getAbsolutePath().equals(cacheKey)) return null;
        if (!mainFile.exists()) return null;
        if (mainFile.lastModified() != cacheMtime || mainFile.length() != cacheLength) return null;
        return cacheState.shellCopy();
    }

    private void remember(LedgerState state) {
        cacheKey = mainFile.getAbsolutePath();
        cacheMtime = mainFile.exists() ? mainFile.lastModified() : -1L;
        cacheLength = mainFile.exists() ? mainFile.length() : -1L;
        cacheState = state.shellCopy();
    }

    private void forget() {
        cacheKey = null;
        cacheState = null;
        cacheMtime = 0L;
        cacheLength = 0L;
    }

    /**
     * 落盘顺序：写临时文件（含 fsync）→ 由临时文件生成备份 → 原子改名为主文件。
     *
     * 之前每保存一次要把整份账本复制两遍（main→bak 一次，写完再 main→bak 一次），
     * 记账越多越慢，而且第二次复制前主文件可能已被改名，属于无效 I/O。
     * 现在固定写一次 + 复制一次；临时文件名固定，失败也不会堆积 .tmp-xxx 垃圾。
     */
    private boolean writeState(LedgerState state) {
        ensureStorageDir();
        File temp = new File(storageDir, MAIN_FILE + ".tmp");
        if (!writeText(temp, serialize(state))) {
            temp.delete();
            forget();
            return false;
        }
        // 备份直接从临时文件复制：内容和即将生效的主文件完全一致，
        // 且首次保存就会生成备份（main 不存在时也有得可恢复）。
        //
        // 不能「先删旧备份再写新的」：删完到写完之间进程被杀，备份就没了。
        // 这里先把旧备份改名藏起来，新备份写成功后再删；写失败就把它换回来。
        // 同目录 rename 是原子的，任何时刻至少有一份完整备份可用。
        File retiredBackup = new File(storageDir, MAIN_FILE + ".bak.old");
        retiredBackup.delete();
        if (backupFile.exists() && !backupFile.renameTo(retiredBackup)) {
            // 改名失败（极少见）时退回删除：旧备份保不住，但至少不会写进旧文件。
            backupFile.delete();
        }
        if (!copyFile(temp, backupFile)) {
            // 新备份没写成：把旧备份换回来，别让用户两头落空。
            backupFile.delete();
            if (retiredBackup.exists()) retiredBackup.renameTo(backupFile);
            temp.delete();
            forget();
            return false;
        }
        retiredBackup.delete();
        if (!temp.renameTo(mainFile)) {
            // 有些文件系统跨挂载点不能 rename，退化为复制。
            if (!copyFile(temp, mainFile)) {
                temp.delete();
                forget();
                return false;
            }
        }
        temp.delete();
        remember(state);
        return true;
    }

    private String writeLegacyBackup(LegacyLedgerSnapshot legacy) {
        File backupDir = new File(storageDir, "legacy-backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        File file = new File(backupDir, "legacy-ledger-" + System.currentTimeMillis() + ".txt");
        StringBuilder builder = new StringBuilder();
        builder.append("format=legacy-ledger-backup\n");
        builder.append("checksum=").append(LedgerCodec.encode(legacy.checksum())).append('\n');
        builder.append("localLedger=").append(LedgerCodec.encode(legacy.localLedger)).append('\n');
        builder.append("captureHistory=").append(LedgerCodec.encode(legacy.captureHistory)).append('\n');
        builder.append("pendingCaptures=").append(LedgerCodec.encode(legacy.pendingCaptures)).append('\n');
        writeText(file, builder.toString());
        return file.getAbsolutePath();
    }

    private void ensureStorageDir() {
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    private String serialize(LedgerState state) {
        StringBuilder builder = new StringBuilder();
        builder.append(HEADER).append('\n');
        builder.append("META\tmigrationComplete\t").append(LedgerCodec.encode(String.valueOf(state.migrationComplete))).append('\n');
        builder.append("META\tlegacyChecksum\t").append(LedgerCodec.encode(state.legacyChecksum)).append('\n');
        builder.append("META\tmigratedAtMillis\t").append(LedgerCodec.encode(String.valueOf(state.migratedAtMillis))).append('\n');
        builder.append("META\tupdatedAtMillis\t").append(LedgerCodec.encode(String.valueOf(state.updatedAtMillis))).append('\n');
        appendSection(builder, "transactions");
        for (TransactionRecord record : state.transactions) {
            builder.append("ROW\t").append(record.encode()).append('\n');
        }
        appendSection(builder, "capture_history");
        for (CaptureRecord record : state.captureHistory) {
            builder.append("ROW\t").append(record.encode()).append('\n');
        }
        appendSection(builder, "outbox");
        for (PendingRecord record : state.pendingCaptures) {
            builder.append("ROW\t").append(record.encode()).append('\n');
        }
        appendSection(builder, "rules");
        for (String row : state.rules) {
            builder.append("ROW\t").append(LedgerCodec.encode(row)).append('\n');
        }
        appendSection(builder, "accounts");
        for (String row : state.accounts) {
            builder.append("ROW\t").append(LedgerCodec.encode(row)).append('\n');
        }
        appendSection(builder, "categories");
        for (String row : state.categories) {
            builder.append("ROW\t").append(LedgerCodec.encode(row)).append('\n');
        }
        return builder.toString();
    }

    private LedgerState parseState(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new LedgerState();
        try (BufferedReader reader = new BufferedReader(new StringReader(raw))) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) return null;

            LedgerState state = new LedgerState();
            String section = "";
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                if (parts.length < 2) continue;
                if ("META".equals(parts[0]) && parts.length >= 3) {
                    String key = LedgerCodec.decode(parts[1]);
                    String value = LedgerCodec.decode(parts[2]);
                    if ("migrationComplete".equals(key)) state.migrationComplete = Boolean.parseBoolean(value);
                    else if ("legacyChecksum".equals(key)) state.legacyChecksum = value;
                    else if ("migratedAtMillis".equals(key)) state.migratedAtMillis = parseLong(value);
                    else if ("updatedAtMillis".equals(key)) state.updatedAtMillis = parseLong(value);
                    continue;
                }
                if ("SECTION".equals(parts[0])) {
                    section = LedgerCodec.decode(parts[1]);
                    continue;
                }
                if (!"ROW".equals(parts[0])) continue;
                String payload = line.substring(4);
                if ("transactions".equals(section)) {
                    TransactionRecord record = TransactionRecord.decode(payload);
                    if (record != null) state.transactions.add(record);
                }
                else if ("capture_history".equals(section)) state.captureHistory.add(CaptureRecord.decode(payload));
                else if ("outbox".equals(section)) state.pendingCaptures.add(PendingRecord.decode(payload));
                else if ("rules".equals(section)) state.rules.add(LedgerCodec.decode(payload));
                else if ("accounts".equals(section)) state.accounts.add(LedgerCodec.decode(payload));
                else if ("categories".equals(section)) state.categories.add(LedgerCodec.decode(payload));
            }
            return state;
        } catch (IOException error) {
            return null;
        }
    }

    private void appendSection(StringBuilder builder, String section) {
        builder.append("SECTION\t").append(LedgerCodec.encode(section)).append('\n');
    }

    private void quarantine(String raw) {
        try {
            File quarantine = new File(storageDir, CORRUPT_PREFIX + System.currentTimeMillis() + ".txt");
            writeText(quarantine, raw);
        } catch (Exception error) {
            // Best effort only.
        }
    }

    private static boolean copyFile(File from, File to) {
        try {
            if (from == null || !from.exists()) return false;
            if (to.getParentFile() != null && !to.getParentFile().exists()) {
                to.getParentFile().mkdirs();
            }
            try (FileInputStream input = new FileInputStream(from); FileOutputStream output = new FileOutputStream(to)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static String readText(File file) {
        if (file == null || !file.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean writeText(File file, String text) {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        // 以前写完关流，再以追加模式重新打开一次只为 fsync——白开一次文件。
        // OutputStreamWriter 不关闭底层流，这里统一在 try-with-resources 里收尾。
        try (FileOutputStream stream = new FileOutputStream(file);
             OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(text);
            writer.flush();
            stream.getFD().sync();
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    private static List<PendingRecord> dedupePending(List<PendingRecord> records) {
        LinkedHashMap<String, PendingRecord> byFingerprint = new LinkedHashMap<>();
        for (PendingRecord record : records) {
            if (record != null && !byFingerprint.containsKey(record.fingerprint)) {
                byFingerprint.put(record.fingerprint, record);
            }
        }
        return new ArrayList<>(byFingerprint.values());
    }

    private static String normalize(String text) {
        return String.valueOf(text == null ? "" : text).replaceAll("\\s+", " ").trim();
    }

    private static final class LedgerState {
        private List<TransactionRecord> transactions = new ArrayList<>();
        private List<CaptureRecord> captureHistory = new ArrayList<>();
        private List<PendingRecord> pendingCaptures = new ArrayList<>();
        private List<String> rules = new ArrayList<>();
        private List<String> accounts = new ArrayList<>();
        private List<String> categories = new ArrayList<>();
        private boolean migrationComplete;
        private String legacyChecksum = "";
        private long migratedAtMillis;
        private long updatedAtMillis;

        private LedgerRepositorySnapshot toSnapshot() {
            List<LedgerEntry> ledgerEntries = new ArrayList<>();
            for (TransactionRecord record : transactions) ledgerEntries.add(record.toLedgerEntry());
            List<CaptureEvent> events = new ArrayList<>();
            for (CaptureRecord record : captureHistory) events.add(record.toCaptureEvent());
            List<String> pending = new ArrayList<>();
            for (PendingRecord record : pendingCaptures) pending.add(record.text);
            return new LedgerRepositorySnapshot(ledgerEntries, events, pending, rules, accounts, categories, legacyChecksum, migrationComplete, migratedAtMillis);
        }

        private static LedgerState fromSnapshot(LedgerRepositorySnapshot snapshot) {
            LedgerState state = new LedgerState();
            for (LedgerEntry entry : snapshot.transactions) state.transactions.add(TransactionRecord.from(entry));
            for (CaptureEvent event : snapshot.captureHistory) state.captureHistory.add(CaptureRecord.from(event));
            for (String item : snapshot.pendingCaptures) state.pendingCaptures.add(PendingRecord.from(item, System.currentTimeMillis()));
            state.rules = new ArrayList<>(snapshot.rules);
            state.accounts = new ArrayList<>(snapshot.accounts);
            state.categories = new ArrayList<>(snapshot.categories);
            state.legacyChecksum = snapshot.legacyChecksum;
            state.migrationComplete = snapshot.migrationComplete;
            state.migratedAtMillis = snapshot.migratedAtMillis;
            state.updatedAtMillis = System.currentTimeMillis();
            return state;
        }

        /** 是否有任何值得恢复的内容（用于判断备份能不能拿来回滚）。 */
        private boolean hasContent() {
            return !transactions.isEmpty()
                    || !captureHistory.isEmpty()
                    || !pendingCaptures.isEmpty()
                    || !rules.isEmpty()
                    || !accounts.isEmpty()
                    || !categories.isEmpty()
                    || migrationComplete;
        }

        /**
         * 外壳副本：所有列表都换成新数组，行对象仍然共享（全程只读使用）。
         * 这样调用方往列表里增删改都不会污染缓存里的那份。
         */
        private LedgerState shellCopy() {
            LedgerState copy = new LedgerState();
            copy.transactions = new ArrayList<>(transactions);
            copy.captureHistory = new ArrayList<>(captureHistory);
            copy.pendingCaptures = new ArrayList<>(pendingCaptures);
            copy.rules = new ArrayList<>(rules);
            copy.accounts = new ArrayList<>(accounts);
            copy.categories = new ArrayList<>(categories);
            copy.migrationComplete = migrationComplete;
            copy.legacyChecksum = legacyChecksum;
            copy.migratedAtMillis = migratedAtMillis;
            copy.updatedAtMillis = updatedAtMillis;
            return copy;
        }

        private boolean hasTransactionId(String id) {
            for (TransactionRecord record : transactions) {
                if (record.id.equals(id)) return true;
            }
            return false;
        }

        /**
         * 指纹相同「且时间接近」才算重复。
         *
         * 指纹里不含时间戳，如果只看指纹，用户在不同时间进行的相同金额、
         * 相同商户的支付（例如每天给同一个人转同样的钱）会被永远吞掉。
         */
        private boolean hasRecentlySameFingerprint(String fingerprint, long occurredAtMillis) {
            if (fingerprint == null || fingerprint.isEmpty()) return false;
            for (TransactionRecord record : transactions) {
                if (!record.fingerprint.equals(fingerprint)) continue;
                if (Math.abs(record.occurredAtMillis - occurredAtMillis) <= FINGERPRINT_DEDUPE_WINDOW_MILLIS) {
                    return true;
                }
            }
            return false;
        }

        private PendingRecord findPendingByFingerprint(String fingerprint) {
            for (PendingRecord record : pendingCaptures) {
                if (record.fingerprint.equals(fingerprint)) return record;
            }
            return null;
        }
    }

    private static final class TransactionRecord {
        private final String id;
        private final long amountCents;
        private final String direction;
        private final String merchant;
        private final String account;
        private final String category;
        private final long occurredAtMillis;
        private final String rawText;
        private final String sourcePackage;
        private final String sourceType;
        private final String fingerprint;
        private final long createdAtMillis;
        private final long updatedAtMillis;
        private final long deletedAtMillis;
        private final boolean retainRawText;
        /** 用户补写的用途。作为第 16 列追加，旧数据（15 列）读出来是空串。 */
        private final String note;

        private TransactionRecord(
                String id,
                long amountCents,
                String direction,
                String merchant,
                String account,
                String category,
                long occurredAtMillis,
                String rawText,
                String sourcePackage,
                String sourceType,
                String fingerprint,
                long createdAtMillis,
                long updatedAtMillis,
                long deletedAtMillis,
                boolean retainRawText
        ) {
            this(id, amountCents, direction, merchant, account, category, occurredAtMillis, rawText,
                    sourcePackage, sourceType, fingerprint, createdAtMillis, updatedAtMillis,
                    deletedAtMillis, retainRawText, "");
        }

        private TransactionRecord(
                String id,
                long amountCents,
                String direction,
                String merchant,
                String account,
                String category,
                long occurredAtMillis,
                String rawText,
                String sourcePackage,
                String sourceType,
                String fingerprint,
                long createdAtMillis,
                long updatedAtMillis,
                long deletedAtMillis,
                boolean retainRawText,
                String note
        ) {
            this.id = safe(id);
            this.amountCents = amountCents;
            this.direction = safe(direction);
            this.merchant = safe(merchant);
            this.account = safe(account);
            this.category = safe(category);
            this.occurredAtMillis = occurredAtMillis;
            this.rawText = safe(rawText);
            this.sourcePackage = safe(sourcePackage);
            this.sourceType = safe(sourceType);
            this.fingerprint = safe(fingerprint);
            this.createdAtMillis = createdAtMillis;
            this.updatedAtMillis = updatedAtMillis;
            this.deletedAtMillis = deletedAtMillis;
            this.retainRawText = retainRawText;
            this.note = safe(note);
        }

        private static TransactionRecord from(LedgerEntry entry) {
            long now = System.currentTimeMillis();
            return new TransactionRecord(
                    entry.id,
                    entry.amountCents,
                    entry.direction,
                    entry.merchant,
                    entry.account,
                    entry.category,
                    entry.occurredAtMillis,
                    entry.rawText,
                    entry.sourcePackage,
                    entry.sourceType,
                    entry.fingerprint.isEmpty() ? LedgerCodec.fingerprint(entry) : entry.fingerprint,
                    entry.createdAtMillis > 0 ? entry.createdAtMillis : now,
                    entry.updatedAtMillis > 0 ? entry.updatedAtMillis : now,
                    entry.deletedAtMillis,
                    entry.retainRawText,
                    entry.note
            );
        }

        private TransactionRecord updatedFrom(LedgerEntry entry) {
            return new TransactionRecord(
                    id,
                    entry.amountCents,
                    entry.direction,
                    entry.merchant,
                    entry.account,
                    entry.category,
                    entry.occurredAtMillis,
                    entry.rawText,
                    entry.sourcePackage,
                    entry.sourceType,
                    fingerprint,
                    createdAtMillis,
                    System.currentTimeMillis(),
                    deletedAtMillis,
                    retainRawText,
                    entry.note
            );
        }

        /** 只换分类，其余字段与指纹保持原样——回填不该把一笔账变成另一笔账。 */
        private TransactionRecord withCategory(String newCategory) {
            return new TransactionRecord(
                    id,
                    amountCents,
                    direction,
                    merchant,
                    account,
                    newCategory,
                    occurredAtMillis,
                    rawText,
                    sourcePackage,
                    sourceType,
                    fingerprint,
                    createdAtMillis,
                    System.currentTimeMillis(),
                    deletedAtMillis,
                    retainRawText,
                    note
            );
        }

        private LedgerEntry toLedgerEntry() {
            return new LedgerEntry(
                    id,
                    amountCents,
                    direction,
                    merchant,
                    account,
                    category,
                    occurredAtMillis,
                    rawText,
                    sourcePackage,
                    sourceType,
                    fingerprint,
                    createdAtMillis,
                    updatedAtMillis,
                    deletedAtMillis,
                    retainRawText,
                    note
            );
        }

        private String encode() {
            return join(
                    id,
                    String.valueOf(amountCents),
                    direction,
                    merchant,
                    account,
                    category,
                    String.valueOf(occurredAtMillis),
                    rawText,
                    sourcePackage,
                    sourceType,
                    fingerprint,
                    String.valueOf(createdAtMillis),
                    String.valueOf(updatedAtMillis),
                    String.valueOf(deletedAtMillis),
                    String.valueOf(retainRawText),
                    note
            );
        }

        /**
         * 解一条交易行；坏行返回 null。
         *
         * 以前坏行会解出「字段全空」的记录：id 空、金额 0、商户空。它照样进了账本，
         * 用户在列表里看到一条「未知商户 ¥0.00」，点了没反应、删也删不掉
         * （id 为空，定位不到）；账本条数、今日支出都被它带着偏。
         * 宁可少一条坏数据，也不要把损坏当成真实账单展示。
         */
        private static TransactionRecord decode(String payload) {
            TransactionRecord record = decodeFields(payload);
            if (record == null) return null;
            if (record.id == null || record.id.trim().isEmpty()) return null;
            if (record.amountCents <= 0) return null;
            return record;
        }

        private static TransactionRecord decodeFields(String payload) {
            String[] fields = payload.split("\t", -1);
            if (fields.length >= 15) {
                // 16 列是加了「用途」之后的新格式；15 列的老数据照常读，用途留空。
                String note = fields.length >= 16 ? LedgerCodec.decode(fields[15]) : "";
                return new TransactionRecord(
                        LedgerCodec.decode(fields[0]),
                        parseLong(LedgerCodec.decode(fields[1])),
                        LedgerCodec.decode(fields[2]),
                        LedgerCodec.decode(fields[3]),
                        LedgerCodec.decode(fields[4]),
                        LedgerCodec.decode(fields[5]),
                        parseLong(LedgerCodec.decode(fields[6])),
                        LedgerCodec.decode(fields[7]),
                        LedgerCodec.decode(fields[8]),
                        LedgerCodec.decode(fields[9]),
                        LedgerCodec.decode(fields[10]),
                        parseLong(LedgerCodec.decode(fields[11])),
                        parseLong(LedgerCodec.decode(fields[12])),
                        parseLong(LedgerCodec.decode(fields[13])),
                        Boolean.parseBoolean(LedgerCodec.decode(fields[14])),
                        note
                );
            }
            if (fields.length >= 8) {
                String id = LedgerCodec.decode(fields[0]);
                long amount = parseLong(LedgerCodec.decode(fields[1]));
                String direction = LedgerCodec.decode(fields[2]);
                String merchant = LedgerCodec.decode(fields[3]);
                String account = LedgerCodec.decode(fields[4]);
                String category = LedgerCodec.decode(fields[5]);
                long occurredAtMillis = parseLong(LedgerCodec.decode(fields[6]));
                String rawText = LedgerCodec.decode(fields[7]);
                long now = System.currentTimeMillis();
                return new TransactionRecord(id, amount, direction, merchant, account, category, occurredAtMillis, rawText, "", "legacy", LedgerCodec.fingerprint(id + "|" + rawText), now, now, 0L, true);
            }
            return null;
        }
    }

    private static final class CaptureRecord {
        private final long timestampMillis;
        private final String status;
        private final String reason;
        private final String packageName;
        private final String text;
        private final String id;
        private final long createdAtMillis;

        private CaptureRecord(long timestampMillis, String status, String reason, String packageName, String text, String id, long createdAtMillis) {
            this.timestampMillis = timestampMillis;
            this.status = safe(status);
            this.reason = safe(reason);
            this.packageName = safe(packageName);
            this.text = safe(text);
            this.id = safe(id);
            this.createdAtMillis = createdAtMillis;
        }

        private static CaptureRecord from(CaptureEvent event) {
            long now = System.currentTimeMillis();
            return new CaptureRecord(
                    event.timestampMillis,
                    event.status,
                    event.reason,
                    event.packageName,
                    event.text,
                    LedgerCodec.fingerprint(event),
                    now
            );
        }

        private CaptureEvent toCaptureEvent() {
            return new CaptureEvent(timestampMillis, status, reason, packageName, text);
        }

        private String encode() {
            return join(
                    String.valueOf(timestampMillis),
                    status,
                    reason,
                    packageName,
                    text,
                    id,
                    String.valueOf(createdAtMillis)
            );
        }

        private static CaptureRecord decode(String payload) {
            String[] fields = payload.split("\t", -1);
            if (fields.length >= 7) {
                return new CaptureRecord(
                        parseLong(LedgerCodec.decode(fields[0])),
                        LedgerCodec.decode(fields[1]),
                        LedgerCodec.decode(fields[2]),
                        LedgerCodec.decode(fields[3]),
                        LedgerCodec.decode(fields[4]),
                        LedgerCodec.decode(fields[5]),
                        parseLong(LedgerCodec.decode(fields[6]))
                );
            }
            if (fields.length >= 5) {
                return new CaptureRecord(
                        parseLong(LedgerCodec.decode(fields[0])),
                        LedgerCodec.decode(fields[1]),
                        LedgerCodec.decode(fields[2]),
                        LedgerCodec.decode(fields[3]),
                        LedgerCodec.decode(fields[4]),
                        LedgerCodec.fingerprint(LedgerCodec.decode(fields[3]) + "|" + LedgerCodec.decode(fields[4])),
                        System.currentTimeMillis()
                );
            }
            return new CaptureRecord(0L, "", "", "", "", "", 0L);
        }
    }

    private static final class PendingRecord {
        private final String id;
        private final String text;
        private final String source;
        private final String fingerprint;
        private final long createdAtMillis;
        private final long claimedAtMillis;

        private PendingRecord(String id, String text, String source, String fingerprint, long createdAtMillis, long claimedAtMillis) {
            this.id = safe(id);
            this.text = safe(text);
            this.source = safe(source);
            this.fingerprint = safe(fingerprint);
            this.createdAtMillis = createdAtMillis;
            this.claimedAtMillis = claimedAtMillis;
        }

        private static PendingRecord from(String text, long createdAtMillis) {
            String normalized = normalize(text);
            return new PendingRecord(
                    fingerprintOf(normalized),
                    normalized,
                    "bridge",
                    fingerprintOf("pending|" + normalized),
                    createdAtMillis,
                    0L
            );
        }

        private PendingRecord withText(String text) {
            String normalized = normalize(text);
            return new PendingRecord(id, normalized, source, fingerprint, createdAtMillis, claimedAtMillis);
        }

        private String encode() {
            return join(
                    id,
                    text,
                    source,
                    fingerprint,
                    String.valueOf(createdAtMillis),
                    String.valueOf(claimedAtMillis)
            );
        }

        private static PendingRecord decode(String payload) {
            String[] fields = payload.split("\t", -1);
            if (fields.length >= 6) {
                return new PendingRecord(
                        LedgerCodec.decode(fields[0]),
                        LedgerCodec.decode(fields[1]),
                        LedgerCodec.decode(fields[2]),
                        LedgerCodec.decode(fields[3]),
                        parseLong(LedgerCodec.decode(fields[4])),
                        parseLong(LedgerCodec.decode(fields[5]))
                );
            }
            if (fields.length >= 1) {
                String text = LedgerCodec.decode(fields[0]);
                return from(text, System.currentTimeMillis());
            }
            return new PendingRecord("", "", "", "", 0L, 0L);
        }

        private static String fingerprintOf(String text) {
            return LedgerCodec.fingerprint(text);
        }
    }

    private static String join(String... fields) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < fields.length; index += 1) {
            if (index > 0) builder.append('\t');
            builder.append(LedgerCodec.encode(fields[index]));
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
