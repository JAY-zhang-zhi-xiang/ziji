package com.ziji.ledger;

/** Suppresses repeated accessibility events from one visible payment-result page. */
final class PaymentPageCaptureGate {
    private final long exitGraceMillis;
    private String activeSignature = "";
    private long nonPaymentSinceMillis;

    PaymentPageCaptureGate(long exitGraceMillis) {
        this.exitGraceMillis = Math.max(0L, exitGraceMillis);
    }

    static boolean isConfirmedExitReason(String reason) {
        return "unknown_source".equals(reason)
                || "chat_window".equals(reason)
                || "order_list".equals(reason)
                || "not_completed".equals(reason)
                || "missing_success".equals(reason);
    }

    synchronized boolean shouldCapture(String signature, long nowMillis) {
        String candidate = signature == null ? "" : signature.trim();
        if (candidate.isEmpty()) return false;
        if (candidate.equals(activeSignature)
                && (nonPaymentSinceMillis == 0L || nowMillis - nonPaymentSinceMillis < exitGraceMillis)) {
            return false;
        }
        activeSignature = candidate;
        nonPaymentSinceMillis = 0L;
        return true;
    }

    synchronized void observeNonPaymentPage(long nowMillis) {
        if (!activeSignature.isEmpty() && nonPaymentSinceMillis == 0L) {
            nonPaymentSinceMillis = nowMillis;
        }
    }
}
