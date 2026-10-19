package com.ziji.ledger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

public final class NotificationListenerRecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        NotificationListenerStatusStore.record(context, "rebind_requested", action);
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName("com.ziji.ledger", "com.ziji.ledger.ZijiNotificationListenerService")
            );
        } catch (RuntimeException error) {
            NotificationListenerStatusStore.record(context, "rebind_failed", error.getClass().getSimpleName());
        }
    }
}
