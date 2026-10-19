package com.ziji.ledger;

import android.content.Context;
import android.content.SharedPreferences;

public final class LedgerAppStorage {
    private LedgerAppStorage() {
    }

    public static LedgerRepository repository(Context context) {
        ensureMigrated(context);
        return LedgerRepository.open(context.getFilesDir());
    }

    public static void ensureMigrated(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BridgeSettings.PREFS, Context.MODE_PRIVATE);
        LegacyLedgerSnapshot legacy = new LegacyLedgerSnapshot(
                prefs.getString(BridgeSettings.KEY_LOCAL_LEDGER, ""),
                prefs.getString(BridgeSettings.KEY_CAPTURE_HISTORY, ""),
                prefs.getString(BridgeSettings.KEY_PENDING_CAPTURES, ""),
                BridgeSettings.MAX_LOCAL_ENTRIES,
                BridgeSettings.MAX_CAPTURE_HISTORY,
                BridgeSettings.MAX_PENDING_CAPTURES
        );

        if (legacy.isEmpty()) return;

        LedgerRepository repo = LedgerRepository.open(context.getFilesDir());
        LedgerMigrationReport report = repo.migrateLegacy(legacy);
        if (report.migrated) {
            prefs.edit()
                    .remove(BridgeSettings.KEY_LOCAL_LEDGER)
                    .remove(BridgeSettings.KEY_CAPTURE_HISTORY)
                    .remove(BridgeSettings.KEY_PENDING_CAPTURES)
                    .commit();
        }
    }
}
