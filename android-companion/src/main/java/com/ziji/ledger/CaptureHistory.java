package com.ziji.ledger;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class CaptureHistory {
    private final int maxEntries;
    private final List<CaptureEvent> entries = new ArrayList<>();

    public CaptureHistory(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public static CaptureHistory fromStoredString(String raw, int maxEntries) {
        List<CaptureEvent> events = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return fromEntries(events, maxEntries);
        for (String line : raw.split("\\n")) {
            CaptureEvent event = decodeEvent(line);
            if (event != null) events.add(event);
        }
        return fromEntries(events, maxEntries);
    }

    public static CaptureHistory fromEntries(List<CaptureEvent> source, int maxEntries) {
        CaptureHistory history = new CaptureHistory(maxEntries);
        if (source == null) return history;
        history.entries.addAll(retainRecentAndPending(source, history.maxEntries));
        return history;
    }

    public static String possibleDuplicateReason(String existingId) {
        return "possible_duplicate:" + (existingId == null ? "" : existingId) + ":" + UUID.randomUUID();
    }

    public static String resolutionReason(CaptureEvent candidate, String outcome) {
        if (candidate == null) return "candidate_resolved::" + (outcome == null ? "" : outcome);
        return "candidate_resolved:" + candidate.timestampMillis + ":" + candidate.reason
                + ":" + (outcome == null ? "" : outcome);
    }

    public static boolean isResolved(CaptureEvent candidate, List<CaptureEvent> events) {
        if (candidate == null || events == null || !"possible_duplicate".equals(candidate.status)) return false;
        String prefix = "candidate_resolved:" + candidate.timestampMillis + ":" + candidate.reason + ":";
        for (CaptureEvent event : events) {
            if (event != null && "resolved".equals(event.status) && event.reason.startsWith(prefix)) return true;
        }
        return false;
    }

    public static boolean isPendingDuplicate(CaptureEvent candidate, List<CaptureEvent> events) {
        return candidate != null && "possible_duplicate".equals(candidate.status) && !isResolved(candidate, events);
    }

    private static List<CaptureEvent> retainRecentAndPending(List<CaptureEvent> source, int maxEntries) {
        List<CaptureEvent> retained = new ArrayList<>();
        int recentNonPending = 0;
        for (CaptureEvent event : source) {
            if (event == null) continue;
            boolean pending = isPendingDuplicate(event, source);
            if (pending || recentNonPending < maxEntries) {
                retained.add(event);
                if (!pending) recentNonPending += 1;
            }
        }
        return retained;
    }

    public void add(CaptureEvent event) {
        if (event == null || event.status.isEmpty()) return;
        entries.add(0, event);
        List<CaptureEvent> retained = retainRecentAndPending(entries, maxEntries);
        entries.clear();
        entries.addAll(retained);
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
