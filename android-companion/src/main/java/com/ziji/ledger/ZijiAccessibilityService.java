package com.ziji.ledger;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 第二条采集通道：无障碍服务。
 *
 * 通知监听的覆盖率受限于「支付 App 是否主动发通知」，而支付成功页一定会出现。
 * 这里监听窗口变化，把前台页面的可见文本交给 PaymentPageMatcher 判定是否是
 * 「支付完成页」，命中后复用 NotificationCaptureProcessor 之外的同一套
 * 账本写入、去重、诊断与桥接同步逻辑。
 *
 * 隐私边界：只读取当前窗口的文本节点用于判定，不做任何截屏、不读取输入框内容、
 * 不上传任何数据；判定失败时按现有诊断规则记录原因，便于自助排查。
 */
public final class ZijiAccessibilityService extends AccessibilityService {
    private static final String TAG = "ZijiLedger";
    /** 同一次支付页面会连续触发多个事件，用冷却时间压掉重复处理。 */
    private static final long COOLDOWN_MILLIS = 1500L;
    /** 主线程提交节流：高频内容变化事件不必每次都进后台线程。 */
    private static final long SUBMIT_THROTTLE_MILLIS = 300L;
    /** 同一成功页的重复事件持续抑制，离开成功页后短暂确认再允许下一笔。 */
    private static final long PAGE_EXIT_GRACE_MILLIS = 3 * 1000L;
    /** 跨通道去重的时间窗：通知与页面几乎同时到达。 */
    private static final long CROSS_CHANNEL_WINDOW_MILLIS = 2 * 60 * 1000L;
    /** 单次遍历最多看多少个节点，防止超长列表页把 CPU 吃满。 */
    private static final int MAX_VISITED_NODES = 300;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile long lastSubmitAt = 0L;
    private volatile long lastHandledAt = 0L;
    private final PaymentPageCaptureGate pageCaptureGate = new PaymentPageCaptureGate(PAGE_EXIT_GRACE_MILLIS);
    /** 付款前确认页暂存的对方/商户名，等结果页出现时补上（结果页通常只有金额没有商户）。 */
    private volatile String pendingMerchant = "";
    private volatile long pendingMerchantAt = 0L;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        NotificationListenerStatusStore.recordAccessibility(this, "accessibility_connected", "accessibility bound");
        Log.i(TAG, "Accessibility capture channel connected");
        BridgeSync.flushIfDue(this, executor, "android-accessibility-recovery");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (packageName.isEmpty()) return;

        // 只处理「窗口变化」类事件；其它类型（滚动、点击等）直接忽略。
        int eventType = event.getEventType();
        boolean windowChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        if (!windowChanged) return;

        // 主线程只做轻量的节流，避免高频内容变化事件把线程池刷爆；
        // 真正的节点遍历与判定放到后台线程，避免阻塞 UI。
        long now = System.currentTimeMillis();
        if (now - lastSubmitAt < SUBMIT_THROTTLE_MILLIS) return;
        lastSubmitAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            NotificationListenerStatusStore.recordAccessibility(this, "accessibility_no_window", packageName);
            return;
        }

        String title = event.getClassName() == null ? "" : event.getClassName().toString();
        submit(packageName, title, root);
    }

    /**
     * 把节点交给后台线程处理。
     *
     * root 是系统对象池里的实例，必须回收。这里用「提交失败也回收」的写法：
     * 服务正在销毁时 executor 会拒绝任务，如果不处理，这个节点就永远回不了池子。
     */
    private void submit(String packageName, String title, AccessibilityNodeInfo root) {
        try {
            executor.execute(() -> {
                try {
                    evaluate(packageName, title, root);
                } catch (RuntimeException error) {
                    // 任何未预料的异常都要留在日志里：无障碍服务一旦静默失效，
                    // 用户看到的是「页面识别开着但就是不记账」，几乎无法自查。
                    Log.w(TAG, "Failed to evaluate window from " + packageName, error);
                }
            });
        } catch (RejectedExecutionException error) {
            recycle(root);
        }
    }

    /** 后台线程：遍历节点 → 判定是否为支付成功页。 */
    private void evaluate(String packageName, String title, AccessibilityNodeInfo root) {
        List<String> nodes = collectText(root);

        // 暂存商户只在一次支付内有效：超过 5 分钟没等到结果页，视为作废。
        // 必须在使用之前清理——先 inspect 再过期的话，过期商户已经被再用了一次。
        if (System.currentTimeMillis() - pendingMerchantAt > 5 * 60 * 1000L) {
            pendingMerchant = "";
        }

        // 先看是不是「付款前确认页」：把对方/商户名暂存下来，等结果页补上。
        // 结果页通常只有「支付成功 + 金额」，商户名只出现在付款前，不暂存就永远判断不出。
        // 聊天页里的「向XX转账」气泡也会命中确认页特征，但那是历史消息，
        // 拿来暂存商户只会污染下一笔真实支付，必须跳过。
        String pageForLookahead = String.join(" ", nodes);
        if (!title.isEmpty()) pageForLookahead = title + " " + pageForLookahead;
        if (!PaymentPageMatcher.isWeChatChatWindow(title)) {
            String prePaymentMerchant = PaymentPageMatcher.detectPrePaymentMerchant(pageForLookahead, nodes);
            if (!prePaymentMerchant.isEmpty()) {
                pendingMerchant = prePaymentMerchant;
                pendingMerchantAt = System.currentTimeMillis();
            }
        }

        PaymentPageMatcher.Result match = PaymentPageMatcher.inspect(packageName, title, nodes, pendingMerchant);
        if (!match.shouldCapture) {
            // 非成功页不占用冷却，避免聊天消息刷新把真正的支付页挤掉。
            if (PaymentPageCaptureGate.isConfirmedExitReason(match.reason)) {
                pageCaptureGate.observeNonPaymentPage(System.currentTimeMillis());
            }
            logRejection(packageName, match);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastHandledAt < COOLDOWN_MILLIS) return;
        lastHandledAt = now;

        String signature = packageName + "|" + match.amountCents + "|" + match.direction;
        if (!pageCaptureGate.shouldCapture(signature, now)) return;

        processMatch(packageName, match);
    }

    /** 记录被拒绝的成功页候选，便于自助排查（非成功页不记录，避免刷屏）。 */
    private void logRejection(String packageName, PaymentPageMatcher.Result match) {
        if (!PaymentPageMatcher.shouldLogRejection(match.reason)) return;
        CaptureHistoryStore.add(this, new CaptureEvent(
                System.currentTimeMillis(),
                "rejected",
                match.reason,
                packageName,
                match.normalizedText
        ));
        NotificationListenerStatusStore.recordAccessibility(this, "rejected", match.reason + " " + packageName);
    }

    private void processMatch(String packageName, PaymentPageMatcher.Result match) {
        LocalLedger ledger = LocalLedgerStore.load(this);
        LedgerEntry entry = TransactionParser.fromPage(
                packageName,
                match.normalizedText,
                match.amountCents,
                match.merchant,
                match.direction,
                // 0 表示让解析器从页面文本里找交易时间，找不到才用当前时间。
                0L,
                LedgerRuleStore.load(this),
                match.purpose.note
        );
        if (entry.amountCents <= 0L) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(), "rejected", "missing_amount", packageName, match.normalizedText
            ));
            return;
        }

        // 使用解析出的交易发生时间，而不是当前系统时间：支付页可能在消费后停留数分钟。
        if (ledger.hasSimilar(
                entry.amountCents,
                entry.direction,
                entry.merchant,
                entry.occurredAtMillis,
                CROSS_CHANNEL_WINDOW_MILLIS,
                entry.sourceType,
                entry.sourcePackage
        )) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(), "duplicate", "cross_channel_duplicate", packageName, match.normalizedText
            ));
            NotificationListenerStatusStore.recordAccessibility(this, "accessibility_duplicate", packageName);
            return;
        }

        LedgerEntry possibleDuplicate = ledger.findPossibleSimilar(entry);
        if (possibleDuplicate != null) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(),
                    "possible_duplicate",
                    CaptureHistory.possibleDuplicateReason(possibleDuplicate.id),
                    packageName,
                    match.normalizedText
            ));
            NotificationListenerStatusStore.recordAccessibility(this, "possible_duplicate", "review_required");
            return;
        }

        // 写盘失败不能再当成「重复」，否则磁盘写不进去时用户完全无感。
        LedgerRepository.AddResult saved = LocalLedgerStore.addResultWithPending(this, entry, match.normalizedText);
        if (saved == LedgerRepository.AddResult.FAILED) {
            Log.e(TAG, "Failed to persist payment page capture");
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(), "failed", "write_failed", packageName, match.normalizedText
            ));
            NotificationListenerStatusStore.recordAccessibility(this, "accessibility_failed", packageName);
            return;
        }
        if (saved == LedgerRepository.AddResult.DUPLICATE) {
            CaptureHistoryStore.add(this, new CaptureEvent(
                    System.currentTimeMillis(), "duplicate", "duplicate", packageName, match.normalizedText
            ));
            return;
        }

        CaptureHistoryStore.add(this, new CaptureEvent(
                System.currentTimeMillis(), "captured", "captured", packageName, match.normalizedText
        ));
        NotificationListenerStatusStore.recordAccessibility(this, "accessibility_captured", packageName);
        Log.i(TAG, "Captured payment page via accessibility: " + match.merchant + " " + match.amountCents);

        BridgeSync.flushIfDue(this, executor, "android-accessibility");
    }

    /**
     * 深度优先（前序）遍历窗口节点，按视觉阅读顺序收集可见文本。
     *
     * 有三个容易踩的点：
     * 1. AccessibilityNodeInfo 来自系统对象池，getChild() 每次返回的都是新实例，
     *    用完必须 recycle()，否则池子会被占满，系统会持续报 "unusable node"。
     * 2. 顺序必须保住：用途抽取靠「商品说明」这个标签节点和它下一行的值节点相邻，
     *    换成 Set 去重或广度优先都会把这条线索打断。
     * 3. 密码输入框要「先判后读」，否则等于把输入框内容读进来了。
     *
     * 传入的 root 以及遍历过程中拿到的所有子节点，均由本方法负责回收。
     */
    private List<String> collectText(AccessibilityNodeInfo root) {
        // 用有序 List 而不是 Set：支付成功页的用途信息是「标签一个节点、值一个节点」，
        // 一旦去重或打乱顺序，「商品说明」和它下面那行「生椰拿铁」就永远配不上对。
        // 相邻完全重复的节点仍然跳过，既压掉了布局容器的重复文本，又保住了配对所需的顺序。
        List<String> texts = new ArrayList<>();
        List<AccessibilityNodeInfo> obtained = new ArrayList<>();
        Deque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        obtained.add(root);
        stack.push(root);
        int visited = 0;
        try {
            while (!stack.isEmpty() && visited < MAX_VISITED_NODES) {
                AccessibilityNodeInfo node = stack.pop();
                if (node == null) continue;
                visited += 1;

                // 密码框整棵子树都不读，符合「不读取输入框内容」的隐私边界。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isPassword()) continue;

                addText(texts, node.getText());
                addText(texts, node.getContentDescription());

                // 逆序压栈：这样弹出顺序就是视觉上的左→右、上→下，
                // 与用户看到的阅读顺序一致，标签和值的相邻关系才是真的。
                for (int index = node.getChildCount() - 1; index >= 0; index -= 1) {
                    AccessibilityNodeInfo child = node.getChild(index);
                    if (child != null) {
                        obtained.add(child);
                        stack.push(child);
                    }
                }
            }
        } catch (RuntimeException error) {
            // 服务被系统断开时节点会立即失效，读文本会抛 IllegalStateException。
            Log.w(TAG, "Failed to read window nodes", error);
        } finally {
            for (AccessibilityNodeInfo node : obtained) recycle(node);
        }
        return texts;
    }

    private static void addText(List<String> sink, CharSequence value) {
        if (value == null) return;
        String text = value.toString().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return;
        int size = sink.size();
        if (size > 0 && sink.get(size - 1).equals(text)) return;
        sink.add(text);
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            node.recycle();
        } catch (RuntimeException error) {
            // 重复回收会抛异常，忽略即可；回收失败不影响判定结果。
        }
    }

    @Override
    public void onInterrupt() {
        NotificationListenerStatusStore.recordAccessibility(this, "accessibility_interrupted", "service interrupted");
    }

    @Override
    public void onDestroy() {
        BridgeSync.cancelRetries(executor);
        executor.shutdownNow();
        super.onDestroy();
    }
}
