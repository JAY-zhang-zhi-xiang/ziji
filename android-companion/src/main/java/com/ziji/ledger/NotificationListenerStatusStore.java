package com.ziji.ledger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 采集通道的运行状态。
 *
 * 两条通道（通知监听 / 页面识别）各自记录状态。
 * 早期版本两条通道共用一个 key，导致页面识别的拒绝原因会覆盖通知监听的状态，
 * 在界面上看起来像是「通知监听失败」，误导排查。这里按通道分前缀存储。
 */
public final class NotificationListenerStatusStore {
    private static final String PREFS = "ziji_listener_status";
    private static final String CHANNEL_NOTIFICATION = "notification";
    private static final String CHANNEL_ACCESSIBILITY = "accessibility";

    private NotificationListenerStatusStore() {
    }

    /** 兼容旧调用：默认记到通知监听通道。 */
    public static void record(Context context, String state, String detail) {
        record(context, CHANNEL_NOTIFICATION, state, detail);
    }

    public static void recordNotification(Context context, String state, String detail) {
        record(context, CHANNEL_NOTIFICATION, state, detail);
    }

    public static void recordAccessibility(Context context, String state, String detail) {
        record(context, CHANNEL_ACCESSIBILITY, state, detail);
    }

    public static void record(Context context, String channel, String state, String detail) {
        if (context == null) return;
        String prefix = prefix(channel);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(prefix + "state", state == null ? "" : state)
                .putString(prefix + "detail", detail == null ? "" : detail)
                .putString(prefix + "updated_at", String.valueOf(System.currentTimeMillis()))
                .apply();
    }

    /** 兼容旧调用：默认读通知监听通道。 */
    public static ListenerStatus snapshot(Context context) {
        return snapshot(context, CHANNEL_NOTIFICATION);
    }

    public static ListenerStatus snapshotNotification(Context context) {
        return snapshot(context, CHANNEL_NOTIFICATION);
    }

    public static ListenerStatus snapshotAccessibility(Context context) {
        return snapshot(context, CHANNEL_ACCESSIBILITY);
    }

    public static ListenerStatus snapshot(Context context, String channel) {
        if (context == null) return ListenerStatus.empty();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefix = prefix(channel);
        return new ListenerStatus(
                prefs.getString(prefix + "state", "unknown"),
                prefs.getString(prefix + "detail", ""),
                parseLong(prefs.getString(prefix + "updated_at", "0"))
        );
    }

    private static String prefix(String channel) {
        return CHANNEL_ACCESSIBILITY.equals(channel) ? "accessibility_" : "notification_";
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException error) {
            return 0L;
        }
    }
}
