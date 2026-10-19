package com.ziji.ledger;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CaptureHistory {
    private final int maxEntries;
    private final List<CaptureEvent> entries = new ArrayList<>();

    public CaptureHistory(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public static CaptureHistory fromStoredString(String raw, int maxEntries) {
        CaptureHistory history = new CaptureHistory(maxEntries);
        if (raw == null || raw.trim().isEmpty()) return history;
        for (String line : raw.split("\\n")) {
            CaptureEvent event = decodeEvent(line);
            if (event != null) history.entries.add(event);
            if (history.entries.size() >= history.maxEntries) break;
        }
        return history;
    }

    public static CaptureHistory fromEntries(List<CaptureEvent> source, int maxEntries) {
        CaptureHistory history = new CaptureHistory(maxEntries);
        if (source == null) return history;
        for (CaptureEvent event : source) {
            if (event != null) history.entries.add(event);
            if (history.entries.size() >= history.maxEntries) break;
        }
        return history;
    }

    public void add(CaptureEvent event) {
        if (event == null || event.status.isEmpty()) return;
        entries.add(0, event);
        while (entries.size() > maxEntries) entries.remove(entries.size() - 1);
    }

    public List<CaptureEvent> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public int size() {
        return entries.size();
    }

    public String toStoredString() {
        List<String> lines = new ArrayList<>();
        for (CaptureEvent event : entries) {
            lines.add(String.join("\t",
                    String.valueOf(event.timestampMillis),
                    encode(event.status),
                    encode(event.reason),
                    encode(event.packageName),
                    encode(event.text)
            ));
        }
        return String.join("\n", lines);
    }

    private static CaptureEvent decodeEvent(String line) {
        try {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5) return null;
            return new CaptureEvent(
                    Long.parseLong(fields[0]),
                    decode(fields[1]),
                    decode(fields[2]),
                    decode(fields[3]),
                    decode(fields[4])
            );
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }
}
