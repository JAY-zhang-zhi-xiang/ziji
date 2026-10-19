package com.ziji.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class LedgerCodec {
    private LedgerCodec() {
    }

    public static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    public static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }

    public static String fingerprint(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + toHex(hash);
        } catch (NoSuchAlgorithmException error) {
            return "hash:" + Integer.toHexString((text == null ? "" : text).hashCode());
        }
    }

    public static String fingerprint(LedgerEntry entry) {
        if (entry == null) return fingerprint("");
        return fingerprint(
                safe(entry.sourcePackage) + "|" +
                safe(entry.sourceType) + "|" +
                entry.amountCents + "|" +
                safe(entry.direction) + "|" +
                safe(entry.merchant) + "|" +
                safe(entry.account) + "|" +
                safe(entry.category) + "|" +
                safe(entry.rawText)
        );
    }

    public static String fingerprint(CaptureEvent event) {
        if (event == null) return fingerprint("");
        return fingerprint(event.timestampMillis + "|" + event.status + "|" + event.reason + "|" + event.packageName + "|" + event.text);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static String checksum(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (values != null) {
                for (String value : values) {
                    digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
            }
            return "sha256:" + toHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            return "hash:" + Integer.toHexString(values == null ? 0 : java.util.Arrays.hashCode(values));
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
