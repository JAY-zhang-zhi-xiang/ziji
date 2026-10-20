package com.ziji.ledger;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LocalLedger {
    private static final long DUPLICATE_WINDOW_MILLIS = 2 * 60 * 1000L;

    /**
     * 跨通道判重的「强证据」时间窗。
     *
     * 同一次支付被通知和无障碍同时看到，两次记录的时间差通常在几秒内。
     * 一旦放宽到分钟级，「同一家店两分钟内买两杯同样价钱的咖啡」就会被判成重复，
     * 静默丢掉一笔——这比多记一条严重得多，因为用户根本不知道自己少了账。
     */
    private static final long STRICT_CROSS_CHANNEL_WINDOW_MILLIS = 30 * 1000L;

    /**
     * 「同商户 + 同金额」判重的窗口。
     *
     * 支付结果页会在屏幕上停留几分钟，期间任何内容变化都会让无障碍通道重新判定；
     * 页面签名去重只有 90 秒，且页面文本每次略有差异会让指纹去重也失效——
     * 用户实测同一笔 ¥14 在 3 分钟内被记了 3 笔（17:46/17:47/17:48）。
     * 同一家店、同一金额、10 分钟内，几乎不可能是两笔独立消费；真要连买两单
     * 同价商品的概率，远低于页面重复触发的概率，这里选择判重（宁可少记可疑重复，
     * 也不能让同一笔支付刷屏——这是用户实际踩到的坑，取舍明确）。
     */
    private static final long SAME_PAYMENT_MERCHANT_WINDOW_MILLIS = 10 * 60 * 1000L;

    private final int maxEntries;
    private final List<LedgerEntry> entries = new ArrayList<>();

    public LocalLedger(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public static LocalLedger fromStoredString(String raw, int maxEntries) {
        LocalLedger ledger = new LocalLedger(maxEntries);
        if (raw == null || raw.trim().isEmpty()) return ledger;
        String[] lines = raw.split("\\n");
        for (String line : lines) {
            LedgerEntry entry = decodeEntry(line);
            if (entry != null && entry.amountCents > 0) ledger.entries.add(entry);
            if (ledger.entries.size() >= ledger.maxEntries) break;
        }
        return ledger;
    }

    public static LocalLedger fromEntries(List<LedgerEntry> source, int maxEntries) {
        LocalLedger ledger = new LocalLedger(maxEntries);
        if (source == null) return ledger;
        for (LedgerEntry entry : source) {
            if (entry != null && entry.amountCents > 0) {
                ledger.entries.add(entry);
            }
            if (ledger.entries.size() >= ledger.maxEntries) break;
        }
        return ledger;
    }

    public boolean add(LedgerEntry entry) {
        if (entry == null || entry.amountCents <= 0 || entry.rawText.trim().isEmpty()) return false;
        for (LedgerEntry existing : entries) {
            // 去重必须「指纹相同」且「时间接近」才算重复。
            // 指纹不含时间戳，若只看指纹，则用户今天和明天各转 0.01 给同一个人时，
            // 第二笔会被永久吞掉——这正是「后续支付记不上」的原因。
            boolean sameFingerprint = !entry.fingerprint.isEmpty()
                    && entry.fingerprint.equals(existing.fingerprint)
                    && Math.abs(existing.occurredAtMillis - entry.occurredAtMillis) <= DUPLICATE_WINDOW_MILLIS;
            boolean sameRawText = existing.rawText.equals(entry.rawText)
                    && Math.abs(existing.occurredAtMillis - entry.occurredAtMillis) <= DUPLICATE_WINDOW_MILLIS;
            if (sameFingerprint || sameRawText) {
                return false;
            }
        }
        entries.add(0, entry);
        while (entries.size() > maxEntries) entries.remove(entries.size() - 1);
        return true;
    }

    /**
     * 跨通道去重：判断是否已经有「同一笔支付」。
     *
     * 同一次支付可能同时被通知监听和无障碍服务看到，两条通道拿到的原文不同、
     * 指纹也不同，单靠 fingerprint 无法识别。这里退一步用「金额 + 方向 + 时间」
     * 做判据。
     *
     * 商户名的作用是「排除」而不是「认定」：两条记录的商户都明确且不同时，
     * 一定是两笔不同的消费；但商户未知不能反过来说一定同一笔——
     * 微信、支付宝的通知里本来就不带商户，拿它当判重依据会把真实消费吞掉。
     *
     * 所以真正的判据是「时间几乎同时」（{@link #STRICT_CROSS_CHANNEL_WINDOW_MILLIS}）：
     * 一条通道记录后，另一条通道在几十秒内报告了同金额同方向的交易，才算同一笔。
     *
     * 注意：只在「已有记录来自另一条通道」时才算重复。
     * 同一通道内的重复由 fingerprint / rawText 去重负责。
     */
    public boolean hasSimilar(long amountCents, String direction, long occurredAtMillis, long windowMillis) {
        return hasSimilar(amountCents, direction, "", occurredAtMillis, windowMillis, "");
    }

    public boolean hasSimilar(
            long amountCents,
            String direction,
            String merchant,
            long occurredAtMillis,
            long windowMillis
    ) {
        return hasSimilar(amountCents, direction, merchant, occurredAtMillis, windowMillis, "");
    }

    /**
     * @param currentSourceType 当前这笔的来源通道（notification / accessibility / share）。
     *                          传空字符串表示不区隔通道（用于测试或向后兼容）。
     */
    public boolean hasSimilar(
            long amountCents,
            String direction,
            String merchant,
            long occurredAtMillis,
            long windowMillis,
            String currentSourceType
    ) {
        return hasSimilar(amountCents, direction, merchant, occurredAtMillis, windowMillis, currentSourceType, "");
    }

    /**
     * 带来源包名的跨来源判重。
     *
     * 旧逻辑「同通道直接跳过」是为了保住「连续两杯同样价格的咖啡」；但它把
     * 「微信支付通知 + 银行卡扣款通知」这种同一笔消费的两条通知也放行了——
     * 两条来源不同 App、文本不同、指纹不同，永远没人拦，账上多出一倍。
     * 收紧为：同通道且同来源 App 才跳过；不同 App 的通知在强证据时间窗内
     * 视为同一笔。同一 App 连续两笔同金额支付不受影响。
     */
    public boolean hasSimilar(
            long amountCents,
            String direction,
            String merchant,
            long occurredAtMillis,
            long windowMillis,
            String currentSourceType,
            String currentSourcePackage
    ) {
        if (amountCents <= 0) return false;
        long window = Math.max(windowMillis, 0L);
        // 调用方给的窗口只是上限，实际判重必须落在强证据窗口内。
        long strictWindow = window <= 0
                ? STRICT_CROSS_CHANNEL_WINDOW_MILLIS
                : Math.min(window, STRICT_CROSS_CHANNEL_WINDOW_MILLIS);
        String wantedDirection = direction == null ? "" : direction;
        String wantedMerchant = merchant == null ? "" : merchant.trim();
        String wantedSource = currentSourceType == null ? "" : currentSourceType;
        String wantedPackage = currentSourcePackage == null ? "" : currentSourcePackage.trim().toLowerCase();
        boolean merchantKnown = !wantedMerchant.isEmpty() && !"未知商户".equals(wantedMerchant);

        for (LedgerEntry existing : entries) {
            if (existing.amountCents != amountCents) continue;
            if (!wantedDirection.isEmpty() && !wantedDirection.equals(existing.direction)) continue;
            boolean existingMerchantKnown = !existing.merchant.isEmpty()
                    && !"未知商户".equals(existing.merchant);
            if (merchantKnown && existingMerchantKnown) {
                // 双方商户都明确：对不上一定是两笔不同的消费；对得上则 10 分钟内
                // 视为同一笔（见 SAME_PAYMENT_MERCHANT_WINDOW_MILLIS 的注释——
                // 支付页停留期间反复触发，是用户实测最多的重复记账来源）。
                // 注意这条规则不区分通道：通知先记、页面后到（或反过来）都会被拦。
                if (wantedMerchant.equals(existing.merchant)
                        && Math.abs(existing.occurredAtMillis - occurredAtMillis)
                                <= SAME_PAYMENT_MERCHANT_WINDOW_MILLIS) {
                    return true;
                }
                continue;
            }
            // 商户有一方不明：退回强证据时间窗判定。
            // 同通道且同来源 App 的记录不算重复：连续两笔同金额的支付要各自入账。
            // 来源 App 不同（微信通知 vs 银行通知）时不再跳过，继续走强证据时间窗判定。
            if (!wantedSource.isEmpty() && wantedSource.equals(existing.sourceType)
                    && (wantedPackage.isEmpty()
                        ? true
                        : wantedPackage.equals(existing.sourcePackage == null ? "" : existing.sourcePackage.toLowerCase()))) {
                continue;
            }
            if (Math.abs(existing.occurredAtMillis - occurredAtMillis) <= strictWindow) return true;
        }
        return false;
    }

    public boolean update(LedgerEntry entry) {
        if (entry == null || entry.id.isEmpty() || entry.amountCents <= 0) return false;
        for (int index = 0; index < entries.size(); index += 1) {
            if (entries.get(index).id.equals(entry.id)) {
                entries.set(index, entry);
                return true;
            }
        }
        return false;
    }

    public boolean removeById(String id) {
        if (id == null || id.trim().isEmpty()) return false;
        for (int index = 0; index < entries.size(); index += 1) {
            if (entries.get(index).id.equals(id)) {
                entries.remove(index);
                return true;
            }
        }
        return false;
    }

    public List<LedgerEntry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public int size() {
        return entries.size();
    }

    public long expenseTotalSince(long startMillis) {
        long total = 0;
        for (LedgerEntry entry : entries) {
            if (entry.isExpense() && entry.occurredAtMillis >= startMillis) total += entry.amountCents;
        }
        return total;
    }

    public String toStoredString() {
        List<String> lines = new ArrayList<>();
        for (LedgerEntry entry : entries) lines.add(encodeEntry(entry));
        return String.join("\n", lines);
    }

    private static String encodeEntry(LedgerEntry entry) {
        return String.join("\t",
                encode(entry.id),
                String.valueOf(entry.amountCents),
                encode(entry.direction),
                encode(entry.merchant),
                encode(entry.account),
                encode(entry.category),
                String.valueOf(entry.occurredAtMillis),
                encode(entry.rawText)
        );
    }

    private static LedgerEntry decodeEntry(String line) {
        try {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 8) return null;
            return new LedgerEntry(
                    decode(fields[0]),
                    Long.parseLong(fields[1]),
                    decode(fields[2]),
                    decode(fields[3]),
                    decode(fields[4]),
                    decode(fields[5]),
                    Long.parseLong(fields[6]),
                    decode(fields[7])
            );
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }
}
