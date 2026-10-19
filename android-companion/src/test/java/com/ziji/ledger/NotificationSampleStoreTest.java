package com.ziji.ledger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class NotificationSampleStoreTest {
    private static int checks = 0;

    public static void main(String[] args) throws Exception {
        roundTripAndStats();
        migratesLegacyNewestFirstFile();
        appendsWithoutRewritingEverything();
        System.out.println("notification sample store tests passed: " + checks);
    }

    private static void roundTripAndStats() throws Exception {
        File dir = Files.createTempDirectory("ziji-sample-store").toFile();
        dir.deleteOnExit();

        NotificationSampleStore.record(dir, sample(1000L, "com.tencent.mm", "captured"));
        NotificationSampleStore.record(dir, sample(2000L, "com.eg.android.alipaygphone", "rejected"));

        List<NotificationSample> samples = NotificationSampleStore.loadRecent(dir, 10);
        assertEquals(2, samples.size(), "samples should round-trip");
        assertEquals("com.eg.android.alipaygphone", samples.get(0).packageName, "latest sample should be first");
        NotificationSampleStore.Stats stats = NotificationSampleStore.snapshot(dir);
        assertEquals(2, stats.total, "total samples should be counted");
        assertEquals(1, stats.captured, "captured samples should be counted");
        assertEquals(50, stats.successRate(), "success rate should be computed");
    }

    /**
     * 旧文件是「最新在最前」的整份重写格式。
     * 换成追加写之后，读到旧文件必须先原样读出（顺序不能反），
     * 再整体重写成新格式，否则老用户的诊断历史会被倒序。
     */
    private static void migratesLegacyNewestFirstFile() throws Exception {
        File dir = Files.createTempDirectory("ziji-sample-legacy").toFile();
        dir.deleteOnExit();
        String body = "ZIJI_NOTIFICATION_SAMPLES\t1\n"
                + legacyLine(9000L, "legacy.newest", "captured") + "\n"
                + legacyLine(8000L, "legacy.older", "rejected") + "\n";
        File file = new File(dir, "notification-samples-v1.log");
        Files.write(file.toPath(), body.getBytes(StandardCharsets.UTF_8));

        List<NotificationSample> before = NotificationSampleStore.loadRecent(dir, 10);
        assertEquals(2, before.size(), "legacy file should be readable");
        assertEquals("legacy.newest", before.get(0).packageName, "legacy order should be newest first");

        NotificationSampleStore.record(dir, sample(10_000L, "brand.new", "captured"));
        List<NotificationSample> after = NotificationSampleStore.loadRecent(dir, 10);
        assertEquals(3, after.size(), "after migration all samples should be kept");
        assertEquals("brand.new", after.get(0).packageName, "new sample should be newest");
        assertEquals("legacy.newest", after.get(1).packageName, "legacy order must survive migration");
        assertEquals("legacy.older", after.get(2).packageName, "oldest legacy sample stays last");

        String migrated = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(migrated.startsWith("ZIJI_NOTIFICATION_SAMPLES\t2"),
                "file should be rewritten in the append-friendly format");
    }

    /** 追加写：每来一条通知只往文件末尾加一行，不再整份重写。 */
    private static void appendsWithoutRewritingEverything() throws Exception {
        File dir = Files.createTempDirectory("ziji-sample-append").toFile();
        dir.deleteOnExit();
        NotificationSampleStore.record(dir, sample(1000L, "first", "captured"));
        File file = new File(dir, "notification-samples-v1.log");
        long lengthAfterFirst = file.length();

        NotificationSampleStore.record(dir, sample(2000L, "second", "captured"));
        assertTrue(file.length() > lengthAfterFirst, "second record should grow the file");

        List<NotificationSample> samples = NotificationSampleStore.loadRecent(dir, 10);
        assertEquals(2, samples.size(), "both samples should be readable");
        assertEquals("second", samples.get(0).packageName, "newest first after append");

        NotificationSampleStore.Stats stats = NotificationSampleStore.snapshot(dir);
        assertEquals(2, stats.captured, "both appends should be counted as captured");
    }

    private static NotificationSample sample(long timestamp, String packageName, String state) {
        return new NotificationSample(
                timestamp,
                packageName,
                "微信支付收款助手",
                "你已向 美团外卖 支付 ¥46.80",
                "",
                "",
                "",
                "Xiaomi",
                "22041216C",
                "34",
                state,
                state.equals("rejected") ? "excluded" : "matched",
                state.equals("rejected") ? 0L : 4680L,
                "美团外卖",
                "expense",
                "微信",
                packageName + " 你已向 美团外卖 支付 ¥46.80"
        );
    }

    private static String legacyLine(long timestamp, String packageName, String state) {
        String[] fields = {
                String.valueOf(timestamp), packageName, "", "", "", "", "", "", "", "",
                state, "", "0", "", "", "", packageName + " text"
        };
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < fields.length; index += 1) {
            if (index > 0) builder.append('\t');
            builder.append(LedgerCodec.encode(fields[index]));
        }
        return builder.toString();
    }

    private static void assertTrue(boolean value, String message) {
        checks += 1;
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        checks += 1;
        if (!expected.equals(actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
}
