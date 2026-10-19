package com.ziji.ledger;

public final class BridgeSetupTextTest {
    public static void main(String[] args) {
        parsesPwaTokenUrl();
        parsesCaptureEndpoint();
        parsesStartLanOutput();
        ignoresBillText();
        System.out.println("bridge setup text tests passed: 10");
    }

    private static void parsesPwaTokenUrl() {
        BridgeSetupText.Result result = BridgeSetupText.parse("http://192.168.1.23:5188/?bridgeToken=ziji-abc123");
        assertEquals("pwa url endpoint", "http://192.168.1.23:5188/api/capture", result.endpoint);
        assertEquals("pwa url token", "ziji-abc123", result.token);
    }

    private static void parsesCaptureEndpoint() {
        BridgeSetupText.Result result = BridgeSetupText.parse("Use this: http://192.168.1.23:5188/api/capture");
        assertEquals("capture endpoint", "http://192.168.1.23:5188/api/capture", result.endpoint);
        assertEquals("capture token", "", result.token);
    }

    private static void parsesStartLanOutput() {
        String output = String.join("\n",
                "Ziji LAN service",
                "Bridge token: ziji-secret",
                "Use this in Android companion:",
                "  http://192.168.1.23:5188/api/capture");
        BridgeSetupText.Result result = BridgeSetupText.parse(output);
        assertEquals("start lan endpoint", "http://192.168.1.23:5188/api/capture", result.endpoint);
        assertEquals("start lan token", "ziji-secret", result.token);
        assertTrue("has endpoint", result.hasEndpoint());
    }

    private static void ignoresBillText() {
        BridgeSetupText.Result result = BridgeSetupText.parse("支付宝 07月07日 向 星巴克 付款 32.00元");
        assertEquals("no endpoint", "", result.endpoint);
        assertEquals("no token", "", result.token);
        assertFalse("no endpoint flag", result.hasEndpoint());
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) throw new AssertionError(name + " should be true");
    }

    private static void assertFalse(String name, boolean value) {
        if (value) throw new AssertionError(name + " should be false");
    }

    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
