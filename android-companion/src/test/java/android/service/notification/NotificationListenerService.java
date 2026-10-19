package android.service.notification;

import android.content.ComponentName;

public class NotificationListenerService {
    public static ComponentName lastRequestedRebind;

    public static void requestRebind(ComponentName componentName) {
        lastRequestedRebind = componentName;
    }
}
