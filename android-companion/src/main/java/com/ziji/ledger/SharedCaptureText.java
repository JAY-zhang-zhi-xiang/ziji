package com.ziji.ledger;

public final class SharedCaptureText {
    private SharedCaptureText() {
    }

    public static String fromParts(Object subject, Object title, Object text) {
        return new NotificationTextBuilder()
                .add(subject)
                .add(title)
                .add(text)
                .build();
    }
}
