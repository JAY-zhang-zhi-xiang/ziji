package com.ziji.ledger;

import java.net.URI;
import java.util.Locale;

public final class BridgeEndpoint {
    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";
    private static final String CAPTURE_PATH = "/api/capture";

    private BridgeEndpoint() {
    }

    public static String normalize(String raw) {
        String endpoint = raw == null ? "" : raw.trim();
        if (endpoint.isEmpty()) return "";

        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(HTTP) && !lower.startsWith(HTTPS)) {
            endpoint = HTTP + endpoint;
        }

        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            String path = uri.getPath() == null ? "" : stripTrailingSlashes(uri.getPath());
            String origin = origin(uri);

            if (path.isEmpty() || "/index.html".equalsIgnoreCase(path)) {
                return origin + CAPTURE_PATH;
            }
            if (path.toLowerCase(Locale.ROOT).endsWith(CAPTURE_PATH)) {
                return origin + path;
            }
            return origin + path;
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    public static String healthUrl(String raw) {
        String endpoint = normalize(raw);
        if (endpoint.isEmpty()) return "";

        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (lower.endsWith(CAPTURE_PATH)) {
            return endpoint.substring(0, endpoint.length() - CAPTURE_PATH.length()) + "/api/health";
        }

        try {
            URI uri = URI.create(endpoint);
            if (uri.getScheme() == null || uri.getHost() == null) return "";
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme() + "://" + uri.getHost() + port + "/api/health";
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    public static String extractToken(String raw) {
        String endpoint = raw == null ? "" : raw.trim();
        if (endpoint.isEmpty()) return "";

        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(HTTP) && !lower.startsWith(HTTPS)) {
            endpoint = HTTP + endpoint;
        }

        try {
            URI uri = URI.create(endpoint);
            String query = uri.getRawQuery();
            if (query == null || query.isEmpty()) return "";
            for (String pair : query.split("&")) {
                String[] parts = pair.split("=", 2);
                String key = decode(parts[0]);
                if ("token".equals(key) || "bridgeToken".equals(key)) {
                    return parts.length > 1 ? decode(parts[1]) : "";
                }
            }
        } catch (IllegalArgumentException error) {
            return "";
        }
        return "";
    }

    private static String stripTrailingSlashes(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String origin(URI uri) {
        String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port;
    }

    private static String decode(String value) {
        try {
            return java.net.URLDecoder.decode(String.valueOf(value), "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }
}
