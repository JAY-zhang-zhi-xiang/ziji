package com.ziji.ledger;

public final class LedgerEntry {
    public final String id;
    public final long amountCents;
    public final String direction;
    public final String merchant;
    public final String account;
    public final String category;
    public final long occurredAtMillis;
    public final String rawText;
    public final String sourcePackage;
    public final String sourceType;
    public final String fingerprint;
    public final long createdAtMillis;
    public final long updatedAtMillis;
    public final long deletedAtMillis;
    public final boolean retainRawText;
    /**
     * 这笔钱的用途（用户补写的备注）。
     *
     * 自动采集只能拿到「金额 + 商户」，商户还经常是空的；
     * 分类是粗粒度的（餐饮/购物），回答不了「这 28 块到底花在什么上」。
     * 所以留一个用户可写的自由文本，和分类一起构成「目的」。
     */
    public final String note;

    public LedgerEntry(
            String id,
            long amountCents,
            String direction,
            String merchant,
            String account,
            String category,
            long occurredAtMillis,
            String rawText
    ) {
        this(id, amountCents, direction, merchant, account, category, occurredAtMillis, rawText,
                "", "", "", occurredAtMillis, occurredAtMillis, 0L, true, "");
    }

    /** 不带用途的旧签名：全部现有调用点保持可用。 */
    public LedgerEntry(
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
                sourcePackage, sourceType, fingerprint, createdAtMillis, updatedAtMillis, deletedAtMillis,
                retainRawText, "");
    }

    public LedgerEntry(
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

    public boolean isExpense() {
        return "expense".equals(direction);
    }

    /**
     * 复制出一份改过「金额 / 商户 / 账户 / 分类 / 用途」的副本。
     *
     * 其余字段（指纹、来源包名、来源类型、创建时间）必须原样保留：
     * 旧版的编辑弹窗用 8 参数构造器重建对象，编辑一次就把来源信息和指纹清空了，
     * 连带的后果是跨通道去重失效。
     */
    public LedgerEntry withAmountPurpose(
            long newAmountCents,
            String newMerchant,
            String newAccount,
            String newCategory,
            String newNote
    ) {
        return new LedgerEntry(
                id,
                newAmountCents > 0 ? newAmountCents : amountCents,
                direction,
                newMerchant == null || newMerchant.trim().isEmpty() ? merchant : newMerchant.trim(),
                newAccount == null || newAccount.trim().isEmpty() ? account : newAccount.trim(),
                newCategory == null || newCategory.trim().isEmpty() ? category : newCategory.trim(),
                occurredAtMillis,
                rawText,
                sourcePackage,
                sourceType,
                fingerprint,
                createdAtMillis,
                updatedAtMillis,
                deletedAtMillis,
                retainRawText,
                newNote == null ? "" : newNote.trim()
        );
    }

    /**
     * 复制出一份改过「金额 / 方向 / 商户 / 账户 / 分类 / 用途 / 时间」的副本。
     *
     * 方向和发生时间是编辑弹窗后补的两项。它们直接决定统计口径：
     * 记反了方向，这笔钱会从支出变收入；日期错了，会落到错误的月份。
     * 以前这两项改不了，用户只能删了重记——重记还要再赌一次解析。
     *
     * 指纹仍然保留原值：编辑不该让旧指纹失效，否则同一条通知重放会被再记一次。
     */
    public LedgerEntry withEdits(
            long newAmountCents,
            String newDirection,
            String newMerchant,
            String newAccount,
            String newCategory,
            String newNote,
            long newOccurredAtMillis
    ) {
        return new LedgerEntry(
                id,
                newAmountCents > 0 ? newAmountCents : amountCents,
                "income".equals(newDirection) ? "income" : "expense",
                newMerchant == null || newMerchant.trim().isEmpty() ? merchant : newMerchant.trim(),
                newAccount == null || newAccount.trim().isEmpty() ? account : newAccount.trim(),
                newCategory == null || newCategory.trim().isEmpty() ? category : newCategory.trim(),
                newOccurredAtMillis > 0 ? newOccurredAtMillis : occurredAtMillis,
                rawText,
                sourcePackage,
                sourceType,
                fingerprint,
                createdAtMillis,
                updatedAtMillis,
                deletedAtMillis,
                retainRawText,
                newNote == null ? "" : newNote.trim()
        );
    }

    /** 只换分类的副本，用于「教一次、同商户的历史一起改」。 */
    public LedgerEntry withCategory(String newCategory) {
        String next = newCategory == null || newCategory.trim().isEmpty()
                ? category
                : newCategory.trim();
        return new LedgerEntry(
                id,
                amountCents,
                direction,
                merchant,
                account,
                next,
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

    /** 还没写清用途：分类停在「其他」且没有任何备注。 */
    public boolean needsPurpose() {
        if (!note.trim().isEmpty()) return false;
        String current = category.trim();
        return current.isEmpty() || "其他".equals(current) || "未分类".equals(current);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
