package com.ziji.ledger;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class PendingCaptureQueue {
    private final int maxEntries;
    private final List<Item> items = new ArrayList<>();

    public static final class Item {
        public final String id;
        public final String text;

        public Item(String id, String text) {
            this.id = String.valueOf(id == null ? "" : id);
            this.text = String.valueOf(text == null ? "" : text);
        }
    }

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

    public static PendingCaptureQueue fromItemsWithIds(List<Item> source, int maxEntries) {
        PendingCaptureQueue queue = new PendingCaptureQueue(maxEntries);
        if (source == null) return queue;
        for (Item item : source) queue.addWithId(item);
        return queue;
    }

    public boolean add(String text) {
        return add("", text);
    }

    public boolean add(String id, String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return false;
        String stableId = id == null ? "" : id.trim();
        for (Item existing : items) {
            if (!stableId.isEmpty() ? existing.id.equals(stableId) : existing.text.equals(normalized)) return false;
        }
        return addWithId(new Item(stableId.isEmpty() ? UUID.randomUUID().toString() : stableId, normalized));
    }

    private boolean addWithId(Item item) {
        if (item == null) return false;
        String normalized = normalize(item.text);
        if (normalized.isEmpty()) return false;
        for (Item existing : items) if (existing.id.equals(item.id)) return false;

        while (items.size() >= maxEntries) {
            items.remove(0);
        }
        items.add(new Item(item.id.isEmpty() ? UUID.randomUUID().toString() : item.id, normalized));
        return true;
    }

    public boolean remove(String text) {
        String normalized = normalize(text);
        for (int index = 0; index < items.size(); index += 1) {
            if (items.get(index).text.equals(normalized)) {
                items.remove(index);
                return true;
            }
        }
        return false;
    }

    public List<String> items() {
        List<String> result = new ArrayList<>();
        for (Item item : items) result.add(item.text);
        return result;
    }

    public List<Item> records() {
        return new ArrayList<>(items);
    }

    public int size() {
        return items.size();
    }

    public String toStoredString() {
        List<String> encoded = new ArrayList<>();
        for (Item item : items) {
            encoded.add(encode(item.text));
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
