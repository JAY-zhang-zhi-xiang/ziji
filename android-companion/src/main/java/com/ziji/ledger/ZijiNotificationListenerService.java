package com.ziji.ledger;

import android.app.Notification;
import android.os.Bundle;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ZijiNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "ZijiLedger";
    private static final long DEDUPE_WINDOW_MILLIS = 2 * 60 * 1000L;
    /** 跨通道去重时间窗：通知与页面识别几乎同时到达。 */
    private static final long CROSS_CHANNEL_WINDOW_MILLIS = 2 * 60 * 1000L;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final NotificationDeduper deduper = new NotificationDeduper(DEDUPE_WINDOW_MILLIS, 64);
    private final NotificationCaptureProcessor processor = new NotificationCaptureProcessor();

    @Override
    public void onListenerConnected() {
        NotificationListenerStatusStore.record(this, "connected", "listener bound");
        super.onListenerConnected();
        BridgeSync.flushIfDue(this, executor, "android-notification-recovery");
    }

    @Override
    public void onListenerDisconnected() {
        NotificationListenerStatusStore.record(this, "disconnected", "listener unbound");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        String title = readExtra(extras, Notification.EXTRA_TITLE);
        String bigText = readExtra(extras, Notification.EXTRA_BIG_TEXT);
        String contentText = readExtra(extras, Notification.EXTRA_TEXT);
        String subText = readExtra(extras, Notification.EXTRA_SUB_TEXT);
        String summaryText = readExtra(extras, Notification.EXTRA_SUMMARY_TEXT);
        String infoText = readExtra(extras, Notification.EXTRA_INFO_TEXT);
        Object textLines = readExtraObject(extras, Notification.EXTRA_TEXT_LINES);
        String text = new NotificationTextBuilder()
                .add(title)
                .add(bigText)
                .add(contentText)
                .add(subText)
                .add(summaryText)
                .add(infoText)
                .add(textLines)
                .add(notification.tickerText)
                .build();

        executor.execute(() -> {
          try {
          LocalLedger ledger = LocalLedgerStore.load(this);
          // processor.process 会先把新捕获放进这份内存账本；跨通道比对必须用处理前快照，
          // 否则新条目会和自己匹配，所有通知都被当成重复跳过。
          LocalLedger previousLedger = LocalLedger.fromEntries(
                  ledger.entries(), Math.max(ledger.size() + 1, 1));
          PendingCaptureQueue queue = loadPendingQueue();
          java.util.List<String> rules = LedgerRuleStore.load(this);
          String stableEventId = LedgerCodec.checksum(
                  "notification", sbn.getKey(), String.valueOf(sbn.getPostTime())
          );
          NotificationCaptureProcessor.Result result = processor.process(
                sbn.getPackageName(),
                title,
                text,
                sbn.getPostTime(),
                System.currentTimeMillis(),
                deduper,
                ledger,
                queue,
                rules,
                stableEventId
          );

        // 只存「支付相关」通知的样本：普通聊天既不是 matched 也不会命中 shouldLogRejection，
        // 不该把好友聊天全文落盘——那和「只读支付通知」的承诺相悖。
        if (result.matched || NotificationMatcher.shouldLogRejection(result.reason)) {
            recordSample(
                    sbn.getPackageName(),
                    title,
                    contentText,
                    bigText,
                    summaryText,
                    infoText,
                    String.valueOf(notification.tickerText == null ? "" : notification.tickerText),
                    result,
                    text
            );
        }

        if (result.matched || NotificationMatcher.shouldLogRejection(result.reason)) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(),
                    captureStatus(result),
                    result.reason,
                    sbn.getPackageName(),
                    result.normalizedText
            ));
        }

        if (result.matched) {
            NotificationListenerStatusStore.record(this, captureStatus(result), result.reason + " " + sbn.getPackageName());
        } else if (NotificationMatcher.shouldLogRejection(result.reason)) {
            NotificationListenerStatusStore.record(this, "rejected", result.reason + " " + sbn.getPackageName());
        }

        if (!result.matched) {
            if (NotificationMatcher.shouldLogRejection(result.reason)) {
                Log.i(TAG, "Skip payment-like notification. reason=" + result.reason + ", package=" + sbn.getPackageName());
            }
            return;
        }
        if (!result.fresh || !result.recorded || result.entry == null) {
            // 去重器 / 本地账本已判定这条记过，不再重复入账。
            Log.i(TAG, "Skip duplicate payment notification");
            return;
        }

        LedgerEntry possibleDuplicate = previousLedger.findPossibleSimilar(result.entry);
        if (possibleDuplicate != null) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(),
                    "possible_duplicate",
                    CaptureHistory.possibleDuplicateReason(possibleDuplicate.id),
                    sbn.getPackageName(),
                    result.normalizedText
            ));
            NotificationListenerStatusStore.recordNotification(this, "possible_duplicate", "review_required");
            return;
        }

        // 跨通道去重：页面识别通道可能已经记过同一笔。
        // 这里复用上面已经加载好的 ledger 快照，不再重复读一次文件；
        // 刚加入的这笔 sourceType 是 notification，会被 hasSimilar 主动跳过，
        // 所以不会自己和自己撞车。
        boolean alreadySeenByPage = previousLedger.hasSimilar(
                result.entry.amountCents,
                result.entry.direction,
                result.entry.merchant,
                result.entry.occurredAtMillis,
                CROSS_CHANNEL_WINDOW_MILLIS,
                "notification",
                sbn.getPackageName()
        );
        if (alreadySeenByPage) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(),
                    "duplicate",
                    "cross_channel_duplicate",
                    sbn.getPackageName(),
                    result.normalizedText
            ));
            NotificationListenerStatusStore.recordNotification(this, "duplicate", "cross_channel_duplicate");
            return;
        }

        // 只有真正落盘成功才推送到同步队列。
        // 以前 add 只返回 boolean，「重复」和「写盘失败」都是 false，
        // 磁盘写不进去时会被当成「已经记过了」，用户完全无感。
        LedgerRepository.AddResult saved = LocalLedgerStore.addResultWithPending(
                this, result.entry, result.normalizedText);
        if (saved == LedgerRepository.AddResult.ADDED || saved == LedgerRepository.AddResult.MERGED) {
            Log.i(TAG, "Saved payment notification to local ledger");
        } else if (saved == LedgerRepository.AddResult.FAILED) {
            Log.e(TAG, "Failed to persist payment notification: " + result.normalizedText);
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(),
                    "failed",
                    "write_failed",
                    sbn.getPackageName(),
                    result.normalizedText
            ));
            NotificationListenerStatusStore.recordNotification(this, "failed", "write_failed");
            return;
        } else {
            NotificationListenerStatusStore.recordNotification(this, "duplicate", "already_recorded");
            return;
        }

        // 回传交给 BridgeSync：节流、读队列、flush、存队列都在那里面，
        // 无障碍通道走的是同一份实现和同一个节流窗口。
        if (BridgeSync.endpoint(this).isEmpty()) {
            Log.w(TAG, "Bridge endpoint is not configured");
            return;
        }
        BridgeSync.flushIfDue(this, executor, "android-notification");
        } catch (RuntimeException error) {
            // 解析或写盘里任何未预料的运行时异常，不能让它静默消失：
            // 那样用户只会看到「这条没记上」，日志里什么都没有。
            Log.w(TAG, "Failed to process notification from " + sbn.getPackageName(), error);
        }
        });
    }

    private static String captureStatus(NotificationCaptureProcessor.Result result) {
        if (!result.matched) return "rejected";
        if (!result.fresh) return "duplicate";
        // matched + fresh 但没入库，说明本地账本判定为相同流水或旧路径重复。
        // 以前这里返回 "ignored"，用户看到的是「忽略了」，会以为这条被丢掉了。
        return result.recorded ? "captured" : "duplicate";
    }

    @Override
    public void onDestroy() {
        BridgeSync.cancelRetries(executor);
        executor.shutdownNow();
        super.onDestroy();
    }

    private static String readExtra(Bundle extras, String key) {
        if (extras == null) return "";
        Object value = extras.get(key);
        return value == null ? "" : value.toString();
    }

    private static Object readExtraObject(Bundle extras, String key) {
        return extras == null ? null : extras.get(key);
    }

    private void recordSample(
            String packageName,
            String title,
            String text,
            String bigText,
            String summaryText,
            String infoText,
            String tickerText,
            NotificationCaptureProcessor.Result result,
            String normalizedText
    ) {
        NotificationSampleStore.record(
                getFilesDir(),
                new NotificationSample(
                        System.currentTimeMillis(),
                        packageName,
                        title,
                        text,
                        bigText,
                        summaryText,
                        tickerText,
                        Build.MANUFACTURER,
                        Build.MODEL,
                        String.valueOf(Build.VERSION.SDK_INT),
                        result.matched ? (result.recorded ? "captured" : "duplicate") : "rejected",
                        result.reason,
                        result.entry == null ? 0L : result.entry.amountCents,
                        result.entry == null ? "" : result.entry.merchant,
                        result.entry == null ? "" : result.entry.direction,
                        result.entry == null ? "" : result.entry.account,
                        normalizedText
                )
        );
    }

    private PendingCaptureQueue loadPendingQueue() {
        return BridgeSync.loadQueue(this);
    }
}
