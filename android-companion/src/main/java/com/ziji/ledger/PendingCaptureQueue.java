package com.ziji.ledger;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

public final class PendingCaptureQueue {
    private final int maxEntries;
    private final List<String> items = new ArrayList<>();

    public PendingCaptureQueue(int maxEntries) {
        this.maxEntries = Math.max(maxEntries, 1);
    }

    public static PendingCaptureQueue fromStoredString(String raw, int maxEntries) {
        PendingCaptureQueue queue = new PendingCaptureQueue(maxEntries);
        if (raw == null || raw.trim().isEmpty()) return queue;

        String[] lines = raw.split("\\n");
        for (String line : lines) {
            queue.add(decode(line));
        }
        return queue;
    }

    public static PendingCaptureQueue fromItems(List<String> source, int maxEntries) {
        PendingCaptureQueue queue = new PendingCaptureQueue(maxEntries);
        if (source == null) return queue;
        for (String item : source) {
            queue.add(item);
        }
        return queue;
    }

    public boolean add(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty() || items.contains(normalized)) return false;

        while (items.size() >= maxEntries) {
            items.remove(0);
        }
        items.add(normalized);
        return true;
    }

    public boolean remove(String text) {
        return items.remove(normalize(text));
    }

    public List<String> items() {
        return new ArrayList<>(items);
    }

    public int size() {
        return items.size();
    }

    public String toStoredString() {
        List<String> encoded = new ArrayList<>();
        for (String item : items) {
            encoded.add(encode(item));
        }
        return String.join("\n", encoded);
    }

    private static String normalize(String text) {
        return String.valueOf(text).replaceAll("\\s+", " ").trim();
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(String.valueOf(text), "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }
}
