package com.ziji.ledger;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public final class LedgerRuleStore {
    private LedgerRuleStore() {
    }

    public static synchronized List<String> load(Context context) {
        return new ArrayList<>(LedgerAppStorage.repository(context).loadRules());
    }

    public static synchronized boolean add(Context context, String keyword, String category) {
        String safeKeyword = safe(keyword).trim();
        String safeCategory = safe(category).trim();
        if (safeKeyword.isEmpty() || safeCategory.isEmpty()) return false;
        List<String> rules = load(context);
        String next = safeKeyword + "\t" + safeCategory;
        for (String rule : rules) {
            if (next.equals(rule)) return false;
        }
        rules.add(next);
        return LedgerAppStorage.repository(context).replaceRules(rules);
    }

    public static String keyword(String rule) {
        String[] fields = safe(rule).split("\\t", 2);
        return fields.length == 2 ? fields[0] : "";
    }

    public static String category(String rule) {
        String[] fields = safe(rule).split("\\t", 2);
        return fields.length == 2 ? fields[1] : "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
