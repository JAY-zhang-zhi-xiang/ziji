package com.ziji.ledger;

public final class PaymentPageCaptureGateTest {
    public static void main(String[] args) {
        PaymentPageCaptureGate gate = new PaymentPageCaptureGate(3_000L);

        assertTrue(gate.shouldCapture("com.tencent.mm|1400|expense", 1_000L), "first success page is captured");
        assertFalse(gate.shouldCapture("com.tencent.mm|1400|expense", 60_000L),
                "same page remains suppressed beyond the old time window");
        assertTrue(gate.shouldCapture("com.tencent.mm|2800|expense", 61_000L),
                "a different transaction signature is not blocked");

        gate.observeNonPaymentPage(70_000L);
        assertFalse(gate.shouldCapture("com.tencent.mm|2800|expense", 72_000L),
                "brief page transitions do not re-open the same capture");
        assertTrue(gate.shouldCapture("com.tencent.mm|2800|expense", 74_000L),
                "the same signature is accepted after leaving the success page");

        gate.observeNonPaymentPage(80_000L);
        assertFalse(gate.shouldCapture("com.tencent.mm|2800|expense", 82_000L),
                "unknown-source page transitions still honor the exit grace period");
        assertTrue(gate.shouldCapture("com.tencent.mm|2800|expense", 84_000L),
                "confirmed exit allows a later identical payment page");

        assertTrue(PaymentPageCaptureGate.isConfirmedExitReason("unknown_source"),
                "an unsupported app confirms leaving the payment page");
        assertFalse(PaymentPageCaptureGate.isConfirmedExitReason("missing_amount"),
                "an incomplete success page does not confirm leaving");

        System.out.println("payment page capture gate tests passed: 9");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertFalse(boolean value, String message) {
        if (value) throw new AssertionError(message);
    }
}
