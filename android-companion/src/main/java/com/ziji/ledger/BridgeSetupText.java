package com.ziji.ledger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BridgeSetupText {
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://[^\\s\\u3000]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{2,5}(?:/[^\\s\\u3000]+)?");
    private static final Pattern TOKEN_LINE_PATTERN = Pattern.compile("(?i)(?:bridge\\s*token|token|口令)\\s*[:：]\\s*([^\\s\\u3000]+)");

    private BridgeSetupText() {
    }

    public static Result parse(String text) {
        String source = String.valueOf(text == null ? "" : text);
        String endpoint = firstEndpoint(source);
        String token = firstToken(source);
        return new Result(endpoint, token);
    }

    private static String firstEndpoint(String source) {
        Matcher urlMatcher = HTTP_URL_PATTERN.matcher(source);
        while (urlMatcher.find()) {
            String normalized = BridgeEndpoint.normalize(cleanCandidate(urlMatcher.group()));
            if (!normalized.isEmpty()) return normalized;
        }

        Matcher hostMatcher = HOST_PORT_PATTERN.matcher(source);
        while (hostMatcher.find()) {
            String normalized = BridgeEndpoint.normalize(cleanCandidate(hostMatcher.group()));
            if (!normalized.isEmpty()) return normalized;
        }
        return "";
    }

    private static String firstToken(String source) {
        Matcher urlMatcher = HTTP_URL_PATTERN.matcher(source);
        while (urlMatcher.find()) {
            String token = BridgeEndpoint.extractToken(cleanCandidate(urlMatcher.group()));
            if (!token.isEmpty()) return token;
        }

        Matcher tokenMatcher = TOKEN_LINE_PATTERN.matcher(source);
        while (tokenMatcher.find()) {
            String token = cleanCandidate(tokenMatcher.group(1));
            if (!token.isEmpty() && !"enabled".equalsIgnoreCase(token) && !"disabled".equalsIgnoreCase(token)) {
                return token;
            }
        }
        return "";
    }

    private static String cleanCandidate(String value) {
        String cleaned = String.valueOf(value).trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(",") || cleaned.endsWith(";")
                || cleaned.endsWith("。") || cleaned.endsWith("，") || cleaned.endsWith("；")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    public static final class Result {
        public final String endpoint;
        public final String token;

        private Result(String endpoint, String token) {
            this.endpoint = endpoint;
            this.token = token;
        }

        public boolean hasEndpoint() {
            return endpoint != null && !endpoint.isEmpty();
        }
    }
}
