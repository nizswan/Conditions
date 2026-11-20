package com.example.conditions;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import java.util.ArrayList;

public class PayloadDraft extends ViewModel {
    final SavedStateHandle state;

    public PayloadDraft(SavedStateHandle state) {
        this.state = state;
        if (!state.contains("uris")) state.set("uris", new ArrayList<String>());
        if (!state.contains("names")) state.set("names", new ArrayList<String>());
        if (!state.contains("library_ids")) {
            ArrayList<Long> selected = new ArrayList<>();
            Long old = state.get("library_id");
            if (old != null) selected.add(old);
            state.set("library_ids", selected);
        }
    }

    ArrayList<String> uris() { return state.get("uris"); }
    ArrayList<String> names() { return state.get("names"); }
    String title() { String value = state.get("title"); return value == null ? "" : value; }
    boolean selectingLibrary() { return Boolean.TRUE.equals(state.get("selecting")); }
    ArrayList<Long> libraries() { return state.get("library_ids"); }
    void selectLibrary(long id, boolean selected) {
        ArrayList<Long> ids = new ArrayList<>(libraries());
        ids.remove(id);
        if (selected) ids.add(id);
        state.set("library_ids", ids);
    }
    void clear() {
        state.set("uris", new ArrayList<String>());
        state.set("names", new ArrayList<String>());
        state.set("library_ids", new ArrayList<Long>());
        state.set("library_id", null);
        state.set("title", "");
        state.set("selecting", false);
    }

    boolean add(String uri, String name) {
        if (uris().contains(uri) || uris().size() >= PayloadTypes.LIMIT) return false;
        ArrayList<String> uris = new ArrayList<>(uris());
        ArrayList<String> names = new ArrayList<>(names());
        uris.add(uri);
        names.add(name);
        state.set("uris", uris);
        state.set("names", names);
        return true;
    }

    void remove(int index) {
        ArrayList<String> uris = new ArrayList<>(uris());
        ArrayList<String> names = new ArrayList<>(names());
        uris.remove(index);
        names.remove(index);
        state.set("uris", uris);
        state.set("names", names);
    }
}
