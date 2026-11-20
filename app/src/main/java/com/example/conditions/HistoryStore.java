package com.example.conditions;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.Locale;

// Keep every visit, not just the value displayed on Vault.
final class HistoryStore extends SQLiteOpenHelper {
    HistoryStore(Context context) {
        super(context, "history.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE visits (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "username TEXT NOT NULL, started_at INTEGER NOT NULL, "
                + "last_seen_at INTEGER NOT NULL, ended_at INTEGER)");
        db.execSQL("CREATE INDEX visits_user ON visits(username, id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    long lastSeen(String username) {
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT last_seen_at FROM visits WHERE username = ? ORDER BY id DESC LIMIT 1",
                new String[]{username.toLowerCase(Locale.ROOT)})) {
            return rows.moveToFirst() ? rows.getLong(0) : 0;
        }
    }

    long startVisit(String username, long now) {
        ContentValues visit = new ContentValues();
        visit.put("username", username.toLowerCase(Locale.ROOT));
        visit.put("started_at", now);
        visit.put("last_seen_at", now);
        return getWritableDatabase().insertOrThrow("visits", null, visit);
    }

    void updateVisit(long id, long now, boolean finished) {
        ContentValues visit = new ContentValues();
        visit.put("last_seen_at", now);
        if (finished) visit.put("ended_at", now);
        getWritableDatabase().update("visits", visit, "id = ?", new String[]{Long.toString(id)});
    }

}
