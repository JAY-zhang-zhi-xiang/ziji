package com.ziji.ledger;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NotificationSampleStore {
    private static final String FILE_NAME = "notification-samples-v1.log";
    /** 旧格式：最新的一条写在最前面，每次写入都要整份重写。 */
    private static final String HEADER_V1 = "ZIJI_NOTIFICATION_SAMPLES\t1";
    /** 新格式：追加写，最新的一条在最后。 */
    private static final String HEADER_V2 = "ZIJI_NOTIFICATION_SAMPLES\t2";
    private static final int MAX_SAMPLES = 400;
    private static final int KEEP_SAMPLES = 250;
    /** 超过这个体积就压实一次，把老样本丢掉。 */
    private static final long MAX_FILE_BYTES = 96L * 1024L;
    /** 读取时的硬上限，防止异常大的文件撑爆内存。 */
    private static final int MAX_READ_LINES = 5000;

    private NotificationSampleStore() {
    }

    public static void record(Context context, NotificationSample sample) {
        if (context == null || sample == null) return;
        record(file(context), sample);
    }

    /**
     * 记录一条通知样本。
     *
     * 旧实现每来一条通知都要「读出最多 400 行 → 逐字段解码 → 整份重写 → fsync」，
     * 而这条路径对**每一条**通知都会走（包括聊天消息这类噪音），
     * 实际开销是几十毫秒的解析加两次全量文件 I/O。
     * 现在改为「追加一行」，只有文件超过体积上限时才压实一次。
     */
    public static void record(File directory, NotificationSample sample) {
        if (directory == null || sample == null) return;
        File file = file(directory);
        ensureParent(file);
        if (isAppendable(file)) {
            append(file, sample);
            if (file.length() > MAX_FILE_BYTES) compact(file);
            return;
        }
        // 首次写入，或者是旧格式（最新在最前）——整份重写成新格式。
        List<NotificationSample> samples = readAll(file);
        samples.add(0, sample);
        writeAll(file, samples);
    }

    public static List<NotificationSample> loadRecent(Context context, int maxEntries) {
        return loadRecent(file(context), maxEntries);
    }

    public static List<NotificationSample> loadRecent(File fileOrDirectory, int maxEntries) {
        List<NotificationSample> samples = readAll(file(fileOrDirectory));
        int limit = Math.max(1, maxEntries);
        if (samples.size() <= limit) return samples;
        return new ArrayList<>(samples.subList(0, limit));
    }

    public static Stats snapshot(Context context) {
        return snapshot(file(context));
    }

    public static Stats snapshot(File fileOrDirectory) {
        List<NotificationSample> samples = loadRecent(fileOrDirectory, MAX_SAMPLES);
        int captured = 0;
        int pending = 0;
        int rejected = 0;
        NotificationSample latest = samples.isEmpty() ? null : samples.get(0);
        for (NotificationSample sample : samples) {
            if ("captured".equals(sample.resultState)) captured += 1;
            else if ("pending_confirmation".equals(sample.resultState)) pending += 1;
            else rejected += 1;
        }
        return new Stats(samples.size(), captured, pending, rejected, latest);
    }

    private static File file(Context context) {
        return file(context == null ? null : context.getFilesDir());
    }

    private static File file(File directory) {
        if (directory == null) return new File(FILE_NAME);
        if (directory.isDirectory()) return new File(directory, FILE_NAME);
        return directory;
    }

    private static List<NotificationSample> readAll(File file) {
        List<NotificationSample> samples = new ArrayList<>();
        if (file == null || !file.exists()) return samples;
        boolean newestLast;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (HEADER_V1.equals(header)) {
                newestLast = false;
            } else if (HEADER_V2.equals(header)) {
                newestLast = true;
            } else {
                return samples;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                NotificationSample sample = decode(line);
                if (sample != null) samples.add(sample);
                if (samples.size() >= MAX_READ_LINES) break;
            }
        } catch (IOException error) {
            return samples;
        }
        // 新格式是追加写，文件里最旧的在最前；统一转成「最新在最前」返回。
        if (newestLast) Collections.reverse(samples);
        return samples;
    }

    private static boolean isAppendable(File file) {
        if (!file.exists() || file.length() == 0L) return false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            return HEADER_V2.equals(reader.readLine());
        } catch (IOException error) {
            return false;
        }
    }

    private static void append(File file, NotificationSample sample) {
        try (FileOutputStream stream = new FileOutputStream(file, true);
             OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(encode(sample));
            writer.write('\n');
            writer.flush();
            stream.getFD().sync();
        } catch (IOException error) {
            // best effort
        }
    }

    /** 文件超过体积上限：只保留最近 KEEP_SAMPLES 条，重写一遍。 */
    private static void compact(File file) {
        writeAll(file, readAll(file));
    }

    private static void ensureParent(File file) {
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
    }

    /** @param samplesNewestFirst 最新在最前的样本列表；落盘时按「最旧在前」写，方便后续追加。 */
    private static void writeAll(File file, List<NotificationSample> samplesNewestFirst) {
        List<NotificationSample> kept = samplesNewestFirst.size() > KEEP_SAMPLES
                ? new ArrayList<>(samplesNewestFirst.subList(0, KEEP_SAMPLES))
                : new ArrayList<>(samplesNewestFirst);
        try {
            ensureParent(file);
            try (FileOutputStream stream = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
                writer.write(HEADER_V2);
                writer.write('\n');
                for (int index = kept.size() - 1; index >= 0; index -= 1) {
                    writer.write(encode(kept.get(index)));
                    writer.write('\n');
                }
                writer.flush();
                stream.getFD().sync();
            }
        } catch (IOException error) {
            // best effort
        }
    }

    private static String encode(NotificationSample sample) {
        return String.join("\t",
                LedgerCodec.encode(String.valueOf(sample.timestampMillis)),
                LedgerCodec.encode(sample.packageName),
                LedgerCodec.encode(sample.title),
                LedgerCodec.encode(sample.text),
                LedgerCodec.encode(sample.bigText),
                LedgerCodec.encode(sample.summaryText),
                LedgerCodec.encode(sample.tickerText),
                LedgerCodec.encode(sample.manufacturer),
                LedgerCodec.encode(sample.model),
                LedgerCodec.encode(sample.sdk),
                LedgerCodec.encode(sample.resultState),
                LedgerCodec.encode(sample.reason),
                LedgerCodec.encode(String.valueOf(sample.amountCents)),
                LedgerCodec.encode(sample.merchant),
                LedgerCodec.encode(sample.direction),
                LedgerCodec.encode(sample.account),
                LedgerCodec.encode(sample.normalizedText)
        );
    }

    private static NotificationSample decode(String line) {
        try {
            String[] fields = line.split("\t", -1);
            if (fields.length != 17) return null;
            return new NotificationSample(
                    parseLong(LedgerCodec.decode(fields[0])),
                    LedgerCodec.decode(fields[1]),
                    LedgerCodec.decode(fields[2]),
                    LedgerCodec.decode(fields[3]),
                    LedgerCodec.decode(fields[4]),
                    LedgerCodec.decode(fields[5]),
                    LedgerCodec.decode(fields[6]),
                    LedgerCodec.decode(fields[7]),
                    LedgerCodec.decode(fields[8]),
                    LedgerCodec.decode(fields[9]),
                    LedgerCodec.decode(fields[10]),
                    LedgerCodec.decode(fields[11]),
                    parseLong(LedgerCodec.decode(fields[12])),
                    LedgerCodec.decode(fields[13]),
                    LedgerCodec.decode(fields[14]),
                    LedgerCodec.decode(fields[15]),
                    LedgerCodec.decode(fields[16])
            );
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    public static final class Stats {
        public final int total;
        public final int captured;
        public final int pending;
        public final int rejected;
        public final NotificationSample latest;

        private Stats(int total, int captured, int pending, int rejected, NotificationSample latest) {
            this.total = total;
            this.captured = captured;
            this.pending = pending;
            this.rejected = rejected;
            this.latest = latest;
        }

        public int successRate() {
            if (total <= 0) return 0;
            return Math.round((captured * 100f) / total);
        }
    }
}
