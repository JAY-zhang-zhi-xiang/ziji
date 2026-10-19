package com.ziji.ledger;

public final class PendingCaptureFlusher {
    private PendingCaptureFlusher() {
    }

    public interface Sender {
        void post(String text) throws Exception;
    }

    public interface Saver {
        void save(PendingCaptureQueue queue) throws Exception;
    }

    public static int flush(PendingCaptureQueue queue, Sender sender, Saver saver) throws Exception {
        int sent = 0;
        for (String pending : queue.items()) {
            sender.post(pending);
            queue.remove(pending);
            saver.save(queue);
            sent += 1;
        }
        return sent;
    }
}
