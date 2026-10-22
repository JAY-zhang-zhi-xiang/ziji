package com.ziji.ledger;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LocalLedger {
    private static final long POSSIBLE_DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1000L;

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
            // 只按事件/流水 ID 幂等；相同文案可能是用户连续完成的两笔真实支付。
            if (!entry.id.isEmpty() && entry.id.equals(existing.id)) return false;
        }
        entries.add(0, entry);
        while (entries.size() > maxEntries) entries.remove(entries.size() - 1);
        return true;
    }

    /**
     * 跨通道去重：判断是否已经有「同一笔支付」。
     *
     * 同一次支付可能同时被通知监听和无障碍服务看到，两条通道拿到的原文不同、
     * 指纹也不同，单靠 fingerprint 无法识别。判据是「金额 + 方向 + 时间 + 商户可信度」，
     * 具体规则见 {@link PaymentIdentity}。
     *
     * windowMillis 只保留在签名里：真正的窗口由 PaymentIdentity 决定。
     * 旧实现在这里把调用方传的 2 分钟又 min 到 30 秒，两条通道的时间精度
     * （页面解析到分钟、通知带秒）一错位就必然漏判——参数留着只会让人误以为可调。
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
     * 「微信支付通知 + 银行短信」这种同一笔消费的两条通知，来源不同 App、文本不同、
     * 指纹不同，只看指纹永远没人拦。这里把来源通道 / 来源包名一起交给
     * {@link PaymentIdentity#isSamePayment}，由它决定「不同 App 算不算两条独立证据」。
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
        String wantedDirection = direction == null ? "" : direction;
        for (LedgerEntry existing : entries) {
            // 判定口径统一在 PaymentIdentity：两条通道、写入口兜底、历史去重共用一份规则。
            // 这里不再自己维护窗口参数——旧实现把调用方传进来的 2 分钟又砍到 30 秒，
            // 而两条通道的时间精度只到分钟，于是每次跨通道判重都恰好差在窗口之外。
            if (PaymentIdentity.isSamePayment(
                    amountCents, wantedDirection, merchant, occurredAtMillis,
                    currentSourceType, currentSourcePackage,
                    existing.amountCents, existing.direction, existing.merchant, existing.occurredAtMillis,
                    existing.sourceType, existing.sourcePackage
            )) {
                return true;
            }
        }
        return false;
    }

    /**
     * 找到一笔「相近但证据不足」的交易，交由用户复核；不把它当重复吞掉。
     * 明确不同的商户排除，强证据重复由 hasSimilar 处理。
     */
    public LedgerEntry findPossibleSimilar(LedgerEntry candidate) {
        if (candidate == null || candidate.amountCents <= 0L) return null;
        for (LedgerEntry existing : entries) {
            if (existing.amountCents != candidate.amountCents
                    || !existing.direction.equals(candidate.direction)) continue;
            long gap = Math.abs(existing.occurredAtMillis - candidate.occurredAtMillis);
            if (gap > POSSIBLE_DUPLICATE_WINDOW_MILLIS) continue;
            if (PaymentIdentity.isSamePayment(
                    existing.amountCents, existing.direction, existing.merchant, existing.occurredAtMillis,
                    existing.sourceType, existing.sourcePackage,
                    candidate.amountCents, candidate.direction, candidate.merchant, candidate.occurredAtMillis,
                    candidate.sourceType, candidate.sourcePackage)) continue;
            if (PaymentIdentity.isSameCaptureSource(
                    existing.sourceType, existing.sourcePackage,
                    candidate.sourceType, candidate.sourcePackage
            )) continue;
            if (PaymentIdentity.isReliableMerchant(existing.merchant)
                    && PaymentIdentity.isReliableMerchant(candidate.merchant)
                    && !PaymentIdentity.sameMerchant(existing.merchant, candidate.merchant)) continue;
            return existing;
        }
        return null;
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
