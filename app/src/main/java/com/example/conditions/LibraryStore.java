package com.example.conditions;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

final class LibraryStore extends SQLiteOpenHelper {
    LibraryStore(Context context) { super(context, "libraries.db", null, 9); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE libraries (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL, title TEXT NOT NULL, rule TEXT NOT NULL DEFAULT '{}', description TEXT NOT NULL DEFAULT '', cover BLOB)");
        createBlocks(db);
        createChoices(db);
        createItems(db);
    }
    private void createItems(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE library_items (id INTEGER PRIMARY KEY AUTOINCREMENT, library_id INTEGER NOT NULL REFERENCES libraries(id) ON DELETE CASCADE, title TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, context TEXT NOT NULL DEFAULT '{}')");
        db.execSQL("CREATE INDEX items_library ON library_items(library_id)");
    }
    private void createBlocks(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE blocks (id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT NOT NULL, title TEXT NOT NULL, rule TEXT NOT NULL)");
    }
    private void createChoices(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE choices (username TEXT NOT NULL, type TEXT NOT NULL, identity TEXT NOT NULL, piece TEXT NOT NULL, PRIMARY KEY(username, type, identity))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE libraries ADD COLUMN rule TEXT NOT NULL DEFAULT '{}'");
            createBlocks(db);
        }
        if (oldVersion < 3) createChoices(db);
        if (oldVersion < 5) db.execSQL("ALTER TABLE libraries ADD COLUMN cover BLOB");
        if (oldVersion < 4) db.execSQL("ALTER TABLE libraries ADD COLUMN description TEXT NOT NULL DEFAULT ''");
        if (oldVersion < 6) createItems(db);
        else {
            if (oldVersion < 8) db.execSQL("ALTER TABLE library_items ADD COLUMN context TEXT NOT NULL DEFAULT '{}'");
            if (oldVersion < 9) {
                db.execSQL("ALTER TABLE library_items RENAME TO old_library_items");
                db.execSQL("DROP INDEX items_library");
                createItems(db);
                db.execSQL("INSERT INTO library_items (id, library_id, title, uri, name, context) "
                        + "SELECT id, library_id, title || '_' || name, uri, name, context FROM old_library_items");
                db.execSQL("DROP TABLE old_library_items");
            }
        }
    }
    Cursor forUser(String username) { return rows("libraries", username); }
    Cursor blocks(String username) { return rows("blocks", username); }
    private Cursor rows(String table, String username) {
        return getReadableDatabase().rawQuery("SELECT id, title, rule" + (table.equals("libraries") ? ", description, cover, (SELECT COUNT(*) FROM library_items WHERE library_id = libraries.id)" : "") + " FROM " + table + " WHERE username = ? ORDER BY id",
                new String[]{username.toLowerCase(Locale.ROOT)});
    }
    long save(String username, long id, String title, JSONObject rule, boolean block) {
        return save(username, id, title, rule, block, null);
    }
    long save(String username, long id, String title, JSONObject rule, boolean block, String description) {
        return save(username, id, title, rule, block, description, null);
    }
    long save(String username, long id, String title, JSONObject rule, boolean block, String description, byte[] cover) {
        ConditionRule.validate(rule);
        ContentValues values = new ContentValues();
        values.put("username", username.toLowerCase(Locale.ROOT)); values.put("title", title.trim()); values.put("rule", rule.toString());
        if (!block && description != null) values.put("description", description.trim());
        if (!block && cover != null) values.put("cover", cover);
        String table = block ? "blocks" : "libraries";
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (id == 0) id = db.insertOrThrow(table, null, values);
            else db.update(table, values, "id = ? AND username = ?", new String[]{"" + id, username.toLowerCase(Locale.ROOT)});
            for (String type : new String[]{"bluetooth", "wifi", "location"}) {
                JSONArray used = new JSONArray(); ConditionRule.collect(rule, type, used);
                for (int i = 0; i < used.length(); i++) {
                    JSONObject p = used.optJSONObject(i);
                    String identity = type.equals("location") ? p.optDouble("lat") + "," + p.optDouble("lon") : p.optString("value");
                    ContentValues choice = new ContentValues(); choice.put("username", username.toLowerCase(Locale.ROOT));
                    choice.put("type", type); choice.put("identity", identity); choice.put("piece", p.toString());
                    db.insertWithOnConflict("choices", null, choice, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    void addItems(String username, PayloadDraft draft, RuleSignals signals) {
        if (draft.libraries().isEmpty()) throw new IllegalArgumentException("Select at least one library.");
        if (draft.uris().size() > PayloadTypes.LIMIT) throw new IllegalArgumentException("A payload can contain up to 10 attachments.");
        if (draft.title().trim().isEmpty() || draft.uris().isEmpty()) throw new IllegalArgumentException("Add a title and attachments first.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String context = signals.snapshot().toString();
            for (long id : draft.libraries()) {
                try (Cursor row = db.rawQuery("SELECT rule FROM libraries WHERE id = ? AND username = ?",
                        new String[]{"" + id, username.toLowerCase(Locale.ROOT)})) {
                    if (!row.moveToFirst() || ConditionRule.evaluate(ConditionRule.read(row.getString(0)), signals) != ConditionRule.State.TRUE)
                        throw new IllegalArgumentException("A selected library is locked or no longer exists. Choose libraries again.");
                }
                for (int i = 0; i < draft.uris().size(); i++) {
                    ContentValues item = new ContentValues();
                    item.put("library_id", id); item.put("title", draft.title().trim() + "_" + draft.names().get(i));
                    item.put("context", context);
                    item.put("uri", draft.uris().get(i)); item.put("name", draft.names().get(i));
                    db.insertOrThrow("library_items", null, item);
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    Cursor items(String username, long libraryId) {
        return getReadableDatabase().rawQuery("SELECT library_items.title, uri, name, library_items.id, library_items.context FROM library_items JOIN libraries ON libraries.id = library_items.library_id WHERE libraries.username = ? AND libraries.id = ? ORDER BY library_items.id",
                new String[]{username.toLowerCase(Locale.ROOT), "" + libraryId});
    }
    private void editableItem(String username, long libraryId, String itemId, RuleSignals signals) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT libraries.rule FROM libraries JOIN library_items ON libraries.id = library_items.library_id WHERE libraries.username = ? AND libraries.id = ? AND library_items.id = ?",
                new String[]{username.toLowerCase(Locale.ROOT), "" + libraryId, itemId})) {
            if (!row.moveToFirst()) throw new IllegalArgumentException("This item is no longer in this library.");
            if (ConditionRule.evaluate(ConditionRule.read(row.getString(0)), signals) != ConditionRule.State.TRUE)
                throw new IllegalArgumentException("This library is locked.");
        }
    }
    void renameItem(String username, long libraryId, String itemId, String title, RuleSignals signals) {
        editableItem(username, libraryId, itemId, signals);
        if (title.trim().isEmpty()) throw new IllegalArgumentException("Enter an item name.");
        ContentValues values = new ContentValues(); values.put("title", title.trim());
        getWritableDatabase().update("library_items", values, "library_id = ? AND id = ?", new String[]{"" + libraryId, itemId});
    }
    void removeItem(String username, long libraryId, String itemId, RuleSignals signals) {
        editableItem(username, libraryId, itemId, signals);
        getWritableDatabase().delete("library_items", "library_id = ? AND id = ?", new String[]{"" + libraryId, itemId});
    }
    void resetLibraries(String username) {
        getWritableDatabase().delete("libraries", "username = ?", new String[]{username.toLowerCase(Locale.ROOT)});
    }
    void delete(String username, long id) {
        getWritableDatabase().delete("libraries", "id = ? AND username = ?", new String[]{"" + id, username.toLowerCase(Locale.ROOT)});
    }
    void setCover(String username, long id, byte[] cover) {
        ContentValues values = new ContentValues();
        if (cover == null) values.putNull("cover"); else values.put("cover", cover);
        getWritableDatabase().update("libraries", values, "id = ? AND username = ?", new String[]{"" + id, username.toLowerCase(Locale.ROOT)});
    }
    void editText(String username, long id, boolean description, String text) {
        ContentValues values = new ContentValues();
        values.put(description ? "description" : "title", text.trim());
        getWritableDatabase().update("libraries", values, "id = ? AND username = ?",
                new String[]{"" + id, username.toLowerCase(Locale.ROOT)});
    }
    // Choices come only from explicitly saved rules, never from a device scan.
    JSONArray choices(String username, String type) {
        JSONArray result = new JSONArray();
        try (Cursor saved = getReadableDatabase().rawQuery("SELECT piece FROM choices WHERE username = ? AND type = ? ORDER BY identity",
                new String[]{username.toLowerCase(Locale.ROOT), type})) {
            while (saved.moveToNext()) result.put(ConditionRule.read(saved.getString(0)));
        }
        for (String table : new String[]{"libraries", "blocks"}) {
            try (Cursor rows = rows(table, username)) {
                while (rows.moveToNext()) ConditionRule.collect(ConditionRule.read(rows.getString(2)), type, result);
            }
        }
        return result;
    }
}
