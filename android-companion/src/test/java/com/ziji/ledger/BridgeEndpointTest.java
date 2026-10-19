package com.ziji.ledger;

public final class BridgeEndpointTest {
    public static void main(String[] args) {
        assertEquals(
                "host with port",
                "http://192.168.1.23:5188/api/capture",
                BridgeEndpoint.normalize("192.168.1.23:5188")
        );
        assertEquals(
                "root url",
                "http://192.168.1.23:5188/api/capture",
                BridgeEndpoint.normalize("http://192.168.1.23:5188/")
        );
        assertEquals(
                "capture url",
                "http://192.168.1.23:5188/api/capture",
                BridgeEndpoint.normalize(" http://192.168.1.23:5188/api/capture/ ")
        );
        assertEquals(
                "custom path stays custom",
                "http://192.168.1.23:5188/custom/capture",
                BridgeEndpoint.normalize("http://192.168.1.23:5188/custom/capture")
        );
        assertEquals(
                "pwa root url with token becomes capture url",
                "http://192.168.1.23:5188/api/capture",
                BridgeEndpoint.normalize("http://192.168.1.23:5188/?bridgeToken=abc123")
        );
        assertEquals(
                "pwa index url becomes capture url",
                "http://192.168.1.23:5188/api/capture",
                BridgeEndpoint.normalize("http://192.168.1.23:5188/index.html?token=abc123")
        );
        assertEquals("blank", "", BridgeEndpoint.normalize("   "));

        assertEquals(
                "health from host",
                "http://192.168.1.23:5188/api/health",
                BridgeEndpoint.healthUrl("192.168.1.23:5188")
        );
        assertEquals(
                "health from root",
                "http://192.168.1.23:5188/api/health",
                BridgeEndpoint.healthUrl("http://192.168.1.23:5188/")
        );
        assertEquals(
                "health from capture",
                "http://192.168.1.23:5188/api/health",
                BridgeEndpoint.healthUrl("http://192.168.1.23:5188/api/capture")
        );
        assertEquals(
                "health from custom path uses origin",
                "http://192.168.1.23:5188/api/health",
                BridgeEndpoint.healthUrl("http://192.168.1.23:5188/custom/capture")
        );
        assertEquals(
                "health from pwa token url",
                "http://192.168.1.23:5188/api/health",
                BridgeEndpoint.healthUrl("http://192.168.1.23:5188/?bridgeToken=abc123")
        );
        assertEquals("blank health", "", BridgeEndpoint.healthUrl("   "));

        assertEquals("extract bridge token", "abc123", BridgeEndpoint.extractToken("http://192.168.1.23:5188/?bridgeToken=abc123"));
        assertEquals("extract token", "abc123", BridgeEndpoint.extractToken("http://192.168.1.23:5188/api/capture?token=abc123"));
        assertEquals("blank token", "", BridgeEndpoint.extractToken("http://192.168.1.23:5188/api/capture"));

        System.out.println("bridge endpoint tests passed: 16");
    }

    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
