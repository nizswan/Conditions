package com.example.conditions;

import android.os.Bundle;
import android.view.View;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.TextPaint;
import com.google.android.material.button.MaterialButton;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {

    // 0 = initial choice, 1 = sign in, 2 = sign up.
    private int screen = 0;
    private Auth auth;
    private boolean busy;
    private String userName = "";
    private String accountUsername = "";
    private long loggedOn;
    private int tab = R.id.vault;
    private HistoryStore history;
    private VisitState visit;
    private boolean visible;
    private boolean signingOut;
    private final android.os.Handler historyHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable historyUpdate = new Runnable() {
        @Override public void run() {
            if (visit.id == 0) return;
            history.updateVisit(visit.id, System.currentTimeMillis(), false);
            historyHandler.postDelayed(this, 30000);
        }
    };

    // Keep the same visit when the screen rotates.
    public static class VisitState extends androidx.lifecycle.ViewModel {
        long id;
        long previous;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        history = new HistoryStore(this);
        visit = new androidx.lifecycle.ViewModelProvider(this).get(VisitState.class);

        auth = new Auth(this);
        // Rebuild the screen using the account saved on this device.
        if (savedInstanceState != null) {
            getSupportFragmentManager().getFragments().forEach(fragment ->
                    getSupportFragmentManager().beginTransaction().remove(fragment).commitNow());
        }
        setContentView(R.layout.fragment_login);
        View mainLayout = findViewById(R.id.login);
        if (savedInstanceState != null) {
            screen = savedInstanceState.getInt("screen", 0);
            tab = savedInstanceState.getInt("tab", R.id.vault);
            if (screen == 3) screen = 1;
        }
        showScreen(screen);
        findViewById(R.id.sign_in).setOnClickListener(view -> {
            if (screen == 0) showScreen(1);
            else submit();
        });
        findViewById(R.id.sign_up).setOnClickListener(view ->
                showScreen(screen == 2 ? 1 : 2));
        ((EditText) findViewById(R.id.password)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateStrength(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        updateStrength(((EditText) findViewById(R.id.password)).getText().toString());

        // Keep future content clear of the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (view, insets) -> {
            Insets systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
            );

            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(
                    systemBars.left,
                    systemBars.top,
                    systemBars.right,
                    Math.max(systemBars.bottom, keyboard.bottom)
            );

            return insets;
        });
        if (auth.hasSession()) {
            busy = true;
            findViewById(R.id.sign_in).setEnabled(false);
            findViewById(R.id.sign_up).setEnabled(false);
            findViewById(R.id.account).setEnabled(false);
            org.json.JSONObject savedUser = auth.getSavedUser();
            if (savedUser != null) {
                accountUsername = savedUser.optString("username");
                loggedOn = savedUser.optLong("last_logged_on", 0);
                showLibrary(savedUser.optString("name"));
            }
            auth.restore((user, error) -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                if (user != null) {
                    accountUsername = user.optString("username");
                    loggedOn = user.optLong("last_logged_on", 0);
                    userName = user.optString("name");
                    if (screen != 3) showLibrary(userName);
                } else {
                    if (screen == 3) {
                        // The server explicitly rejected the saved session.
                        finishHistoryVisit();
                        screen = 0;
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                        recreate();
                        return;
                    }
                    findViewById(R.id.sign_in).setEnabled(true);
                    findViewById(R.id.sign_up).setEnabled(true);
                    findViewById(R.id.account).setEnabled(true);
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void submit() {
        if (busy) return;
        String name = ((EditText) findViewById(R.id.name)).getText().toString().trim();
        String username = ((EditText) findViewById(R.id.username)).getText().toString();
        String password = ((EditText) findViewById(R.id.password)).getText().toString();
        if (!username.matches("[A-Za-z0-9]{1,32}")) {
            Toast.makeText(this, "Username: letters and numbers only, up to 32 characters.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (screen == 2 && (name.isEmpty() || name.length() > 100)) {
            Toast.makeText(this, "Enter a name (up to 100 characters).", Toast.LENGTH_LONG).show();
            return;
        }
        if (screen == 2) {
            int missing = 0;
            if (password.length() < 8) missing = R.string.password_short;
            else if (!password.matches("(?s).*[A-Za-z].*")) missing = R.string.password_letter;
            else if (!password.matches("(?s).*[0-9].*")) missing = R.string.password_number;
            if (missing != 0) {
                Toast.makeText(this, missing, Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (password.isEmpty()) {
            Toast.makeText(this, "Enter your password.", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText confirm = findViewById(R.id.confirm);
        com.google.android.material.textfield.TextInputLayout confirmField =
                findViewById(R.id.confirm_field);
        confirmField.setError(null);
        if (screen == 2 && !password.equals(confirm.getText().toString())) {
            confirmField.setError(getString(R.string.password_mismatch));
            confirm.requestFocus();
            return;
        }
        busy = true;
        findViewById(R.id.sign_in).setEnabled(false);
        findViewById(R.id.sign_up).setEnabled(false);
        findViewById(R.id.account).setEnabled(false);
        auth.submit(screen == 2, name, username, password, (user, error) -> {
            if (isFinishing() || isDestroyed()) return;
            busy = false;
            if (error != null) {
                findViewById(R.id.sign_in).setEnabled(true);
                findViewById(R.id.sign_up).setEnabled(true);
                findViewById(R.id.account).setEnabled(true);
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            } else {
                ((EditText) findViewById(R.id.password)).setText("");
                ((EditText) findViewById(R.id.confirm)).setText("");
                accountUsername = user.optString("username");
                loggedOn = user.optLong("last_logged_on", 0);
                showLibrary(user.optString("name"));
            }
        });
    }

    public void updateProfile(String field, String value, Auth.Result result) {
        auth.updateProfile(field, value, (user, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (user != null) {
                accountUsername = user.optString("username");
                userName = user.optString("name");
            }
            result.complete(user, error);
            if (user != null) recreate();
        });
    }

    public void deleteAccount(Auth.Result result) {
        signingOut = true;
        finishHistoryVisit();
        auth.deleteAccount((user, error) -> {
            if (isFinishing() || isDestroyed()) return;
            if (error != null) {
                signingOut = false;
                updateHistory();
                result.complete(null, error);
                return;
            }
            try (LibraryStore libraries = new LibraryStore(this)) {
                android.database.sqlite.SQLiteDatabase db = libraries.getWritableDatabase();
                db.beginTransaction();
                try {
                    for (String table : new String[]{"libraries", "blocks", "choices"})
                        db.delete(table, "username = ?", new String[]{accountUsername});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            history.getWritableDatabase().delete("visits", "username = ?", new String[]{accountUsername});
            getSharedPreferences("conditions_" + accountUsername, MODE_PRIVATE).edit().clear().commit();
            getViewModelStore().clear();
            result.complete(null, null);
            signOut();
        });
    }

    public void signOut() {
        signingOut = true;
        finishHistoryVisit();
        auth.signOut(() -> {
            if (isFinishing() || isDestroyed()) return;
            getSupportFragmentManager().getFragments().forEach(fragment ->
                    getSupportFragmentManager().beginTransaction().remove(fragment).commitNow());
            screen = 0;
            userName = "";
            accountUsername = "";
            loggedOn = 0;
            recreate();
        });
    }

    private void showLibrary(String name) {
        screen = 3;
        userName = name;
        if (visible) updateHistory();
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(findViewById(android.R.id.content).getWindowToken(), 0);
        setContentView(R.layout.screen_home);
        View home = findViewById(R.id.main_home);
        View navBar = findViewById(R.id.nav_bar);
        int navPadding = navBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(home, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, 0);
            navBar.setPadding(navBar.getPaddingLeft(), navBar.getPaddingTop(),
                    navBar.getPaddingRight(), navPadding + bars.bottom);
            return insets;
        });
        for (int id : new int[]{R.id.vault, R.id.create, R.id.settings, R.id.web}) {
            findViewById(id).setOnClickListener(view -> openTab(view.getId()));
        }
        openTab(tab);
    }

    public void createPayloadInLibrary(long libraryId) {
        PayloadDraft draft = new androidx.lifecycle.ViewModelProvider(this).get("payload_" + accountUsername, PayloadDraft.class);
        java.util.ArrayList<Long> selected = new java.util.ArrayList<>();
        selected.add(libraryId);
        draft.state.set("library_ids", selected);
        draft.state.set("selecting", false);
        openTab(R.id.create);
    }

    private void openTab(int id) {
        if (id == tab && getSupportFragmentManager().findFragmentById(R.id.home) != null) {
            return;
        }
        tab = id;
        androidx.fragment.app.Fragment page;
        if (id == R.id.create) page = new UploadFragment();
        else if (id == R.id.settings) page = new SettingsFragment();
        else if (id == R.id.web) page = new ContextWebFragment();
        else page = new LibraryFragment();
        Bundle details = new Bundle();
        details.putString("name", userName);
        details.putString("username", accountUsername);
        details.putLong("logged_on", loggedOn);
        page.setArguments(details);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.home, page).commit();
        int[] buttons = {R.id.vault, R.id.create, R.id.settings, R.id.web};
        int[] labels = {R.id.vault_label, R.id.create_label, R.id.settings_label, R.id.web_label};
        for (int i = 0; i < buttons.length; i++) {
            boolean selected = buttons[i] == id;
            int color = Color.parseColor(selected ? "#306BF3" : "#626873");
            android.widget.ImageButton button = findViewById(buttons[i]);
            button.setImageTintList(ColorStateList.valueOf(color));
            button.setSelected(selected);
            ((TextView) findViewById(labels[i])).setTextColor(color);
        }
    }

    private void showScreen(int screen) {
        this.screen = screen;
        boolean signingUp = screen == 2;
        int form = screen == 0 ? View.GONE : View.VISIBLE;

        findViewById(R.id.name_field).setVisibility(signingUp ? View.VISIBLE : View.GONE);
        findViewById(R.id.username_field).setVisibility(form);
        findViewById(R.id.password_field).setVisibility(form);
        findViewById(R.id.confirm_field).setVisibility(signingUp ? View.VISIBLE : View.GONE);
        findViewById(R.id.strength).setVisibility(signingUp ? View.VISIBLE : View.GONE);
        ((com.google.android.material.textfield.TextInputLayout)
                findViewById(R.id.confirm_field)).setError(null);
        ((EditText) findViewById(R.id.password)).setImeOptions(signingUp
                ? android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                : android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        TextView account = findViewById(R.id.account);
        account.setVisibility(form);
        String prompt = getString(signingUp ? R.string.have_account : R.string.no_account);
        String link = getString(signingUp ? R.string.sign_in : R.string.sign_up);
        SpannableString text = new SpannableString(prompt + " " + link);
        text.setSpan(new ClickableSpan() {
            @Override public void onClick(View view) {
                if (!busy) showScreen(signingUp ? 1 : 2);
            }
            @Override public void updateDrawState(TextPaint paint) {
                paint.setColor(Color.parseColor("#306BF3"));
                paint.setUnderlineText(false);
                paint.setFakeBoldText(true);
            }
        }, prompt.length() + 1, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        account.setText(text);
        account.setMovementMethod(LinkMovementMethod.getInstance());

        MaterialButton submit = findViewById(R.id.sign_in);
        MaterialButton swap = findViewById(R.id.sign_up);
        swap.setVisibility(screen == 0 ? View.VISIBLE : View.GONE);
        submit.setText(signingUp ? R.string.sign_up : R.string.sign_in);
        swap.setText(signingUp ? R.string.sign_in : R.string.sign_up);
        submit.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(signingUp ? "#892CF4" : "#306BF3")));
        swap.setBackgroundTintList(ColorStateList.valueOf(
                Color.parseColor(signingUp ? "#306BF3" : "#892CF4")));
    }

    private void updateStrength(String password) {
        int score = (password.length() >= 8 ? 1 : 0)
                + (password.matches("(?s).*[A-Za-z].*") ? 1 : 0)
                + (password.matches("(?s).*[0-9].*") ? 1 : 0);
        String color = score == 3 ? "#2E7D32" : score == 2 ? "#D9A000" : "#D32F2F";
        int[] ticks = {R.id.tick1, R.id.tick2, R.id.tick3};
        for (int i = 0; i < ticks.length; i++) {
            findViewById(ticks[i]).setBackgroundColor(
                    Color.parseColor(i < score ? color : "#DDE1E7"));
        }
        TextView status = findViewById(R.id.strength_status);
        status.setText(getString(R.string.password_score, score));
        status.setTextColor(Color.parseColor(score == 0 ? "#626873" : color));
    }

    public void updateHistory() {
        boolean enabled = visible && !signingOut && screen == 3 && !accountUsername.isEmpty()
                && new ConditionSettings(this, accountUsername).isEnabled("history");
        if (!enabled) {
            finishHistoryVisit();
            return;
        }
        if (visit.id == 0) {
            visit.previous = history.lastSeen(accountUsername);
            visit.id = history.startVisit(accountUsername, System.currentTimeMillis());
        }
        historyHandler.removeCallbacks(historyUpdate);
        historyHandler.postDelayed(historyUpdate, 30000);
    }

    public long previousVisit() {
        return visit.previous;
    }

    private void finishHistoryVisit() {
        historyHandler.removeCallbacks(historyUpdate);
        if (visit.id != 0) {
            history.updateVisit(visit.id, System.currentTimeMillis(), true);
            visit.id = 0;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        updateHistory();
    }

    @Override protected void onStop() {
        visible = false;
        historyHandler.removeCallbacks(historyUpdate);
        if (!isChangingConfigurations()) finishHistoryVisit();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("screen", screen);
        outState.putInt("tab", tab);
    }

    @Override
    protected void onDestroy() {
        historyHandler.removeCallbacks(historyUpdate);
        history.close();
        auth.close();
        super.onDestroy();
    }
}
