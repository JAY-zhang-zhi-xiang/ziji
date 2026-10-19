package com.ziji.ledger;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.NotificationListenerService;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class NotificationListenerRecoveryTest {
    public static void main(String[] args) {
        FakeContext context = new FakeContext();

        NotificationListenerStatusStore.record(context, "connected", "listener bound");
        ListenerStatus status = NotificationListenerStatusStore.snapshot(context);
        assertEquals("connected", status.state, "status state should persist");
        assertEquals("listener bound", status.detail, "status detail should persist");
        assertTrue(status.updatedAtMillis > 0, "status timestamp should be set");

        NotificationListenerService.lastRequestedRebind = null;
        new NotificationListenerRecoveryReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(NotificationListenerService.lastRequestedRebind != null, "boot completed should request rebind");

        ComponentName component = NotificationListenerService.lastRequestedRebind;
        assertTrue(component.flattenToString().contains("ZijiNotificationListenerService"), "rebind target should be listener service");

        NotificationListenerService.lastRequestedRebind = null;
        new NotificationListenerRecoveryReceiver().onReceive(context, new Intent("ignored"));
        assertTrue(NotificationListenerService.lastRequestedRebind == null, "unrelated broadcast should be ignored");

        System.out.println("notification listener recovery tests passed: 5");
    }

    private static final class FakeContext extends Context {
        private final File filesDir = new File(System.getProperty("java.io.tmpdir"), "ziji-listener-test");
        private final Map<String, SharedPreferences> prefs = new HashMap<>();

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            SharedPreferences existing = prefs.get(name);
            if (existing != null) return existing;
            SharedPreferences created = new InMemorySharedPreferences();
            prefs.put(name, created);
            return created;
        }

        @Override
        public File getFilesDir() {
            return filesDir;
        }
    }

    private static final class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String getString(String key, String defValue) {
            return values.containsKey(key) ? values.get(key) : defValue;
        }

        @Override
        public Editor edit() {
            return new InMemoryEditor();
        }

        private final class InMemoryEditor implements Editor {
            private final Map<String, String> pending = new HashMap<>();
            private final Map<String, Boolean> removals = new HashMap<>();

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                pending.remove(key);
                removals.put(key, true);
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                for (String key : removals.keySet()) {
                    values.remove(key);
                }
                values.putAll(pending);
            }
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
