package com.ziji.ledger;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeClientTest {
    public static void main(String[] args) throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>("");
        AtomicReference<String> receivedToken = new AtomicReference<>("");
        AtomicReference<String> receivedHealthToken = new AtomicReference<>("");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/health", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-Ziji-Token");
            receivedHealthToken.set(token);
            boolean authorized = "secret-token".equals(token);
            byte[] response = ("{\"ok\":true,\"requiresToken\":true,\"authorized\":" + authorized + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.getResponseBody().close();
        });
        server.createContext("/api/capture", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Ziji-Token"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, 0);
            exchange.getResponseBody().close();
        });

        server.start();
        try {
            int port = server.getAddress().getPort();
            BridgeClient client = new BridgeClient(
                    "http://127.0.0.1:" + port + "/api/capture",
                    "secret-token",
                    "android-companion-test"
            );

            BridgeClient.HealthStatus health = client.checkHealth();
            assertEquals("health token", "secret-token", receivedHealthToken.get());
            assertEquals("health status", 200, health.statusCode);
            assertEquals("health ok", true, health.ok);
            assertEquals("health requires token", true, health.requiresToken);
            assertEquals("health authorized", true, health.authorized);

            BridgeClient.HealthStatus badHealth = new BridgeClient(
                    "http://127.0.0.1:" + port + "/api/capture",
                    "wrong-token",
                    "android-companion-test"
            ).checkHealth();
            assertEquals("bad health authorized", false, badHealth.authorized);

            client.postCapture("test \"coffee\"\n88.88 CNY");
            String firstBody = receivedBody.get();
            // 通知原文里可能带制表符等控制字符，漏转义会把 JSON 写坏。
            client.postCapture("tab\there\u0001ctrl");
            String secondBody = receivedBody.get();
            assertEquals("token", "secret-token", receivedToken.get());
            assertEquals(
                    "body",
                    "{\"source\":\"android-companion-test\",\"text\":\"test \\\"coffee\\\"\\n88.88 CNY\"}",
                    firstBody
            );
            assertEquals(
                    "control chars body",
                    "{\"source\":\"android-companion-test\",\"text\":\"tab\\there\\u0001ctrl\"}",
                    secondBody
            );
            client.postCapture("event-42", "same text");
            assertEquals(
                    "capture id body",
                    "{\"source\":\"android-companion-test\",\"captureId\":\"event-42\",\"text\":\"same text\"}",
                    receivedBody.get()
            );
        } finally {
            server.stop(0);
        }
        System.out.println("bridge client tests passed: 4");
    }

    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertEquals(String name, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertEquals(String name, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
