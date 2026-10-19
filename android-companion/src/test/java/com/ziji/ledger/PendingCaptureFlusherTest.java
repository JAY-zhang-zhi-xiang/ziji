package com.ziji.ledger;

import java.util.ArrayList;
import java.util.List;

public final class PendingCaptureFlusherTest {
    public static void main(String[] args) throws Exception {
        savesAfterEachSuccessfulPost();
        keepsUnsentItemsAfterFailure();
        System.out.println("pending capture flusher tests passed: 8");
    }

    private static void savesAfterEachSuccessfulPost() throws Exception {
        PendingCaptureQueue queue = new PendingCaptureQueue(5);
        queue.add("支付宝 付款 32.00元");
        queue.add("微信 支付 46.80元");

        List<String> saved = new ArrayList<>();
        int sent = PendingCaptureFlusher.flush(
                queue,
                (text) -> {
                },
                (updatedQueue) -> saved.add(updatedQueue.toStoredString())
        );

        assertEquals("sent all items", 2, sent);
        assertEquals("queue empty after success", 0, queue.size());
        assertEquals("saved after each success", 2, saved.size());
        assertEquals("last saved snapshot empty", "", saved.get(saved.size() - 1));
    }

    private static void keepsUnsentItemsAfterFailure() {
        PendingCaptureQueue queue = new PendingCaptureQueue(5);
        queue.add("支付宝 付款 32.00元");
        queue.add("微信 支付 46.80元");
        queue.add("银行 消费 128.60元");

        List<String> saved = new ArrayList<>();
        try {
            PendingCaptureFlusher.flush(
                    queue,
                    (text) -> {
                        if (text.contains("微信")) {
                            throw new RuntimeException("network down");
                        }
                    },
                    (updatedQueue) -> saved.add(updatedQueue.toStoredString())
            );
            throw new AssertionError("flush should fail on second item");
        } catch (RuntimeException expected) {
            assertEquals("failure message", "network down", expected.getMessage());
        } catch (Exception error) {
            throw new AssertionError("unexpected checked exception", error);
        }

        assertEquals("saved only first success", 1, saved.size());
        PendingCaptureQueue restored = PendingCaptureQueue.fromStoredString(saved.get(0), 5);
        assertEquals("restored queue keeps unsent items", 2, restored.size());
        assertFalse("sent item was removed from saved queue", restored.remove("支付宝 付款 32.00元"));
        assertTrue("failed item remains", restored.remove("微信 支付 46.80元"));
    }

    private static void assertTrue(String name, boolean value) {
        if (!value) throw new AssertionError(name + " should be true");
    }

    private static void assertFalse(String name, boolean value) {
        if (value) throw new AssertionError(name + " should be false");
    }

    private static void assertEquals(String name, int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }

    private static void assertEquals(String name, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + " expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
