package com.ziji.ledger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NotificationDeduper {
    private final long windowMillis;
    private final int maxEntries;
    private final LinkedHashMap<String, Long> recent = new LinkedHashMap<>();

    public NotificationDeduper(long windowMillis, int maxEntries) {
        this.windowMillis = Math.max(windowMillis, 1L);
        this.maxEntries = Math.max(maxEntries, 1);
    }

    public synchronized boolean shouldPost(String text, long nowMillis) {
        String key = normalize(text);
        if (key.isEmpty()) return false;

        pruneExpired(nowMillis);
        if (recent.containsKey(key)) return false;

        recent.put(key, nowMillis);
        trimOldest();
        return true;
    }

    private void pruneExpired(long nowMillis) {
        Iterator<Map.Entry<String, Long>> iterator = recent.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (nowMillis - entry.getValue() > windowMillis) {
                iterator.remove();
            }
        }
    }

    private void trimOldest() {
        Iterator<String> iterator = recent.keySet().iterator();
        while (recent.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String normalize(String text) {
        return String.valueOf(text).replaceAll("\\s+", " ").trim();
    }
}
