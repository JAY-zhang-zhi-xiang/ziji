package com.ziji.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class BridgeClient {
    private final String endpoint;
    private final String token;
    private final String source;

    public BridgeClient(String endpoint, String token, String source) {
        this.endpoint = endpoint;
        this.token = token == null ? "" : token;
        this.source = source == null ? "android-notification" : source;
    }

    public void postCapture(String text) throws IOException {
        postCapture("", text);
    }

    public void postCapture(String captureId, String text) throws IOException {
        if (isBlank(endpoint)) {
            throw new IOException("Bridge endpoint is empty");
        }

        byte[] body = jsonBody(captureId, text).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!isBlank(token)) {
                connection.setRequestProperty("X-Ziji-Token", token);
            }

            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int status = connection.getResponseCode();
            // 成功时也要把响应体读完：不读空就断，keep-alive 连接会一直挂着，
            // 高频记账时 socket 会越积越多，最终连不上桥接服务。
            readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw new IOException("Bridge returned HTTP " + status);
            }
        } finally {
            connection.disconnect();
        }
    }

    public HealthStatus checkHealth() throws IOException {
        if (isBlank(endpoint)) {
            throw new IOException("Bridge endpoint is empty");
        }

        String healthEndpoint = BridgeEndpoint.healthUrl(endpoint);
        if (isBlank(healthEndpoint)) {
            throw new IOException("Bridge health endpoint is invalid");
        }

        HttpURLConnection connection = (HttpURLConnection) URI.create(healthEndpoint).toURL().openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            if (!isBlank(token)) {
                connection.setRequestProperty("X-Ziji-Token", token);
            }

            int status = connection.getResponseCode();
            String body = readBody(connection, status);
            if (status < 200 || status >= 300) {
                throw new IOException("Bridge health returned HTTP " + status);
            }

            return new HealthStatus(
                    status,
                    body.contains("\"ok\":true"),
                    body.contains("\"requiresToken\":true"),
                    !body.contains("\"authorized\":false")
            );
        } finally {
            connection.disconnect();
        }
    }

    private String jsonBody(String captureId, String text) {
        String id = isBlank(captureId) ? "" : ",\"captureId\":\"" + escape(captureId) + "\"";
        return "{\"source\":\"" + escape(source) + "\"" + id + ",\"text\":\"" + escape(text) + "\"}";
    }

    /**
     * 手工拼 JSON 的转义。
     *
     * 除了引号和换行，还必须处理制表符等控制字符——通知原文里出现 \t 时，
     * 漏转义会把 JSON 写坏，服务端直接解析失败。
     */
    private static String escape(String value) {
        String source = String.valueOf(value == null ? "" : value);
        StringBuilder builder = new StringBuilder(source.length() + 16);
        for (int index = 0; index < source.length(); index += 1) {
            char character = source.charAt(index);
            switch (character) {
                case '\\': builder.append("\\\\"); break;
                case '"': builder.append("\\\""); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                default:
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
            }
        }
        return builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";

        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    public static final class HealthStatus {
        public final int statusCode;
        public final boolean ok;
        public final boolean requiresToken;
        public final boolean authorized;

        private HealthStatus(int statusCode, boolean ok, boolean requiresToken, boolean authorized) {
            this.statusCode = statusCode;
            this.ok = ok;
            this.requiresToken = requiresToken;
            this.authorized = authorized;
        }
    }
}
