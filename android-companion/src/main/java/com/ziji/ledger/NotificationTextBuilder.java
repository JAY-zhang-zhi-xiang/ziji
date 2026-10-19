package com.ziji.ledger;

import java.util.ArrayList;
import java.util.List;

public final class NotificationTextBuilder {
    private final List<String> parts = new ArrayList<>();

    public NotificationTextBuilder add(Object value) {
        if (value == null) return this;
        if (value instanceof CharSequence[]) {
            for (CharSequence item : (CharSequence[]) value) add(item);
            return this;
        }
        if (value instanceof Object[]) {
            for (Object item : (Object[]) value) add(item);
            return this;
        }

        String normalized = normalize(String.valueOf(value));
        if (!normalized.isEmpty() && !parts.contains(normalized)) {
            parts.add(normalized);
        }
        return this;
    }

    public String build() {
        return String.join(" ", parts);
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
