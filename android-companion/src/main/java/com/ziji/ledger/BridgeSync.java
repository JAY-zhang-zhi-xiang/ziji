package com.ziji.ledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.RejectedExecutionException;

import java.util.concurrent.Executor;

/**
 * 采集通道 → 桥接服务的回传。
 *
 * 通知监听和无障碍服务原来各有一份几乎完全相同的实现（读偏好、读待发队列、
 * flush、存队列），节流状态还各自独立——两个通道同时捕获时会对同一个
 * 桥接服务发两轮请求。这里合并成一份实现，共用一个节流窗口。
 *
 * 并发约束：所有 flush 都提交到调用方那个「单线程采集 executor」上执行，
 * 不能另起线程。因为 flush 结束时用「当前队列对象」整份覆盖磁盘上的队列，
 * 如果它与采集写入并发，新采集到的那条会被旧快照覆盖掉。
 */
final class BridgeSync {
    private static final String TAG = "ZijiLedger";
    /** 回传节流：一次 flush 可能因网络超时占用十几秒。 */
    private static final long FLUSH_THROTTLE_MILLIS = 10 * 1000L;

    private static volatile long lastFlushAt = 0L;
    private static final Handler RETRY_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RETRY_SCHEDULED = new AtomicBoolean(false);
    private static volatile Executor retryExecutor;
    private static volatile Runnable retryTask;

    private BridgeSync() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(BridgeSettings.PREFS, Context.MODE_PRIVATE);
    }

    static String endpoint(Context context) {
        String value = prefs(context).getString(BridgeSettings.KEY_ENDPOINT, "");
        return value == null ? "" : value.trim();
    }

    static String token(Context context) {
        String value = prefs(context).getString(BridgeSettings.KEY_TOKEN, "");
        return value == null ? "" : value;
    }

    static PendingCaptureQueue loadQueue(Context context) {
        return LedgerAppStorage.repository(context).loadPendingQueue(BridgeSettings.MAX_PENDING_CAPTURES);
    }

    /**
     * 到点就把待发队列推给桥接服务。
     *
     * 没配置 endpoint 或还在节流窗口内时直接返回，不做任何 I/O。
     */
    static void flushIfDue(Context context, Executor executor, String source) {
        String endpoint = endpoint(context);
        if (endpoint.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastFlushAt < FLUSH_THROTTLE_MILLIS) return;
        lastFlushAt = now;
        String token = token(context);
        try {
        executor.execute(() -> {
            try {
                BridgeClient client = new BridgeClient(endpoint, token, source);
                // 逐条发送、逐条原子删除：不再 load 整份队列后又整份 save 覆盖——
                // 那会跟并发的采集追加互相踩，把刚捕获的新条目一起抹掉。
                PendingCaptureQueue queue = loadQueue(context);
                int sent = 0;
                for (PendingCaptureQueue.Item pending : queue.records()) {
                    client.postCapture(pending.id, pending.text);
                    LedgerAppStorage.repository(context).removePendingCaptureById(pending.id);
                    sent += 1;
                }
                if (sent > 0) Log.i(TAG, "Posted pending captures from " + source + ": " + sent);
            } catch (Exception error) {
                // Allow the next capture to retry immediately; otherwise a
                // transient failure can leave the outbox idle indefinitely.
                lastFlushAt = 0L;
                scheduleRetry(context.getApplicationContext(), executor, source);
                Log.w(TAG, "Failed to post pending captures from " + source, error);
            }
        });
        } catch (RejectedExecutionException error) {
            lastFlushAt = 0L;
            Log.i(TAG, "Skip bridge retry because capture service stopped");
        }
    }

    private static void scheduleRetry(Context context, Executor executor, String source) {
        if (!RETRY_SCHEDULED.compareAndSet(false, true)) return;
        retryExecutor = executor;
        Runnable task = () -> {
            RETRY_SCHEDULED.set(false);
            retryTask = null;
            retryExecutor = null;
            flushIfDue(context, executor, source);
        };
        retryTask = task;
        RETRY_HANDLER.postDelayed(task, 15 * 1000L);
    }

    static void cancelRetries(Executor executor) {
        if (retryExecutor != executor) return;
        Runnable task = retryTask;
        if (task != null) RETRY_HANDLER.removeCallbacks(task);
        retryTask = null;
        retryExecutor = null;
        RETRY_SCHEDULED.set(false);
    }
}
