package com.ziji.ledger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ShareCaptureActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void handleShareIntent(Intent intent) {
        String text = extractSharedText(intent);
        if (text.isEmpty()) {
            Toast.makeText(this, "没有收到可入账的分享文本。", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(BridgeSettings.PREFS, MODE_PRIVATE);
        BridgeSetupText.Result setup = BridgeSetupText.parse(text);
        if (setup.hasEndpoint()) {
            String token = setup.token.isEmpty() ? prefs.getString(BridgeSettings.KEY_TOKEN, "") : setup.token;
            prefs.edit()
                    .putString(BridgeSettings.KEY_ENDPOINT, setup.endpoint)
                    .putString(BridgeSettings.KEY_TOKEN, token == null ? "" : token)
                    .commit();
            Toast.makeText(this, "可选的电脑同步配置已保存。", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }

        // 0 表示让解析器从分享文本里找交易时间，找不到才用当前时间。
        LedgerEntry localEntry = TransactionParser.parse("", text, 0L, LedgerRuleStore.load(this));
        LedgerRepository.AddResult localResult = LocalLedgerStore.addExplicitWithPending(this, localEntry, text);
        boolean recordedLocally = localResult == LedgerRepository.AddResult.ADDED
                || localResult == LedgerRepository.AddResult.MERGED;

        String endpoint = prefs.getString(BridgeSettings.KEY_ENDPOINT, "");
        String token = prefs.getString(BridgeSettings.KEY_TOKEN, "");
        if (endpoint == null || endpoint.trim().isEmpty()) {
            Toast.makeText(this, localResultMessage(recordedLocally, localEntry), Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return;
        }

        executor.execute(() -> {
            try {
                flushPendingCaptures(prefs, endpoint, token);
                runOnUiThread(() -> {
                    Toast.makeText(
                            ShareCaptureActivity.this,
                            recordedLocally ? "已记入手机账本，并完成可选同步。" : localResultMessage(false, localEntry),
                            Toast.LENGTH_LONG
                    ).show();
                    startActivity(new Intent(ShareCaptureActivity.this, SettingsActivity.class));
                    finish();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Toast.makeText(
                            ShareCaptureActivity.this,
                            recordedLocally ? "已记入手机账本；电脑同步暂时不可用。" : localResultMessage(false, localEntry),
                            Toast.LENGTH_LONG
                    ).show();
                    startActivity(new Intent(ShareCaptureActivity.this, SettingsActivity.class));
                    finish();
                });
            }
        });
    }

    private static String localResultMessage(boolean recorded, LedgerEntry entry) {
        if (recorded) return "已自动记入手机账本。";
        if (entry.amountCents <= 0) return "没有识别到金额，请检查分享文本。";
        return "这条账单已经记过了。";
    }

    private String extractSharedText(Intent intent) {
        if (intent == null) return "";
        return SharedCaptureText.fromParts(
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT),
                intent.getCharSequenceExtra(Intent.EXTRA_TITLE),
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
        );
    }

    private int flushPendingCaptures(SharedPreferences prefs, String endpoint, String token) throws Exception {
        PendingCaptureQueue queue = loadPendingQueue();
        BridgeClient client = new BridgeClient(endpoint, token, "android-share");
        // 逐条发送、逐条原子删除，避免整份覆盖与并发采集互相踩丢条目。
        int sent = 0;
        for (PendingCaptureQueue.Item pending : queue.records()) {
            client.postCapture(pending.id, pending.text);
            LedgerAppStorage.repository(this).removePendingCaptureById(pending.id);
            sent += 1;
        }
        return sent;
    }

    private PendingCaptureQueue loadPendingQueue() {
        return LedgerAppStorage.repository(this).loadPendingQueue(BridgeSettings.MAX_PENDING_CAPTURES);
    }
}
