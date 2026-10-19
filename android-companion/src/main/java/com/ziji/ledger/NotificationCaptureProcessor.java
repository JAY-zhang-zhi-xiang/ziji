package com.ziji.ledger;

import java.util.List;

public final class NotificationCaptureProcessor {
    public Result process(
            String packageName,
            String title,
            String text,
            long occurredAtMillis,
            long receivedAtMillis,
            NotificationDeduper deduper,
            LocalLedger ledger,
            PendingCaptureQueue queue
    ) {
        return process(packageName, title, text, occurredAtMillis, receivedAtMillis, deduper, ledger, queue, null);
    }

    public Result process(
            String packageName,
            String title,
            String text,
            long occurredAtMillis,
            long receivedAtMillis,
            NotificationDeduper deduper,
            LocalLedger ledger,
            PendingCaptureQueue queue,
            List<String> rules
    ) {
        NotificationMatcher.MatchResult match = NotificationMatcher.inspect(packageName, title, text);
        if (!match.shouldCapture()) {
            return Result.rejected(match.reason(), match.normalizedText());
        }
        if (!deduper.shouldPost(match.normalizedText(), receivedAtMillis)) {
            return Result.duplicate(match.normalizedText());
        }

        // 通知里通常写着真实交易时间，比「通知下发时间」更准：
        // 23:58 付款、00:02 才到通知的情况，用下发时间会把账记到第二天。
        long occurredAt = TransactionParser.resolveOccurredAt(
                match.normalizedText(),
                occurredAtMillis > 0 ? occurredAtMillis : receivedAtMillis
        );
        LedgerEntry entry = TransactionParser.parse(packageName, match.normalizedText(), occurredAt, rules);
        if (entry.amountCents <= 0L) {
            return Result.rejected("missing_amount", match.normalizedText());
        }
        boolean recorded = ledger.add(entry);
        boolean queued = recorded && queue.add(match.normalizedText());
        return Result.captured(match.normalizedText(), entry, recorded, queued);
    }

    public static final class Result {
        public final boolean matched;
        public final boolean fresh;
        public final boolean recorded;
        public final boolean queued;
        public final String reason;
        public final String normalizedText;
        public final LedgerEntry entry;

        private Result(
                boolean matched,
                boolean fresh,
                boolean recorded,
                boolean queued,
                String reason,
                String normalizedText,
                LedgerEntry entry
        ) {
            this.matched = matched;
            this.fresh = fresh;
            this.recorded = recorded;
            this.queued = queued;
            this.reason = reason;
            this.normalizedText = normalizedText;
            this.entry = entry;
        }

        private static Result rejected(String reason, String normalizedText) {
            return new Result(false, false, false, false, reason, normalizedText, null);
        }

        private static Result duplicate(String normalizedText) {
            return new Result(true, false, false, false, "duplicate", normalizedText, null);
        }

        private static Result captured(String normalizedText, LedgerEntry entry, boolean recorded, boolean queued) {
            return new Result(true, true, recorded, queued, "captured", normalizedText, entry);
        }
    }
}
