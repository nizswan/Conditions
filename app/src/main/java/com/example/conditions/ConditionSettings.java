package com.example.conditions;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

// Each account keeps its own choices, even after signing out.
final class ConditionSettings {
    private final SharedPreferences preferences;

    ConditionSettings(Context context, String username) {
        preferences = context.getSharedPreferences(
                "conditions_" + username.toLowerCase(Locale.ROOT), Context.MODE_PRIVATE);
    }

    boolean isEnabled(String condition) {
        return preferences.getBoolean(condition, condition.equals("time"));
    }

    void setEnabled(String condition, boolean enabled) {
        preferences.edit().putBoolean(condition, enabled).apply();
    }
}
