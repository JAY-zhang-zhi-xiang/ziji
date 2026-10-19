package com.ziji.ledger;

public final class NotificationSample {
    public final long timestampMillis;
    public final String packageName;
    public final String title;
    public final String text;
    public final String bigText;
    public final String summaryText;
    public final String tickerText;
    public final String manufacturer;
    public final String model;
    public final String sdk;
    public final String resultState;
    public final String reason;
    public final long amountCents;
    public final String merchant;
    public final String direction;
    public final String account;
    public final String normalizedText;

    public NotificationSample(
            long timestampMillis,
            String packageName,
            String title,
            String text,
            String bigText,
            String summaryText,
            String tickerText,
            String manufacturer,
            String model,
            String sdk,
            String resultState,
            String reason,
            long amountCents,
            String merchant,
            String direction,
            String account,
            String normalizedText
    ) {
        this.timestampMillis = timestampMillis;
        this.packageName = safe(packageName);
        this.title = safe(title);
        this.text = safe(text);
        this.bigText = safe(bigText);
        this.summaryText = safe(summaryText);
        this.tickerText = safe(tickerText);
        this.manufacturer = safe(manufacturer);
        this.model = safe(model);
        this.sdk = safe(sdk);
        this.resultState = safe(resultState);
        this.reason = safe(reason);
        this.amountCents = amountCents;
        this.merchant = safe(merchant);
        this.direction = safe(direction);
        this.account = safe(account);
        this.normalizedText = safe(normalizedText);
    }

    public boolean parsedSuccessfully() {
        return "captured".equals(resultState) && amountCents > 0 && !"unknown".equals(merchant);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
