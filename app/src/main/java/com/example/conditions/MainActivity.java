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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    private final ActivityResultLauncher<String> networkPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) submit();
                else Toast.makeText(this, "Local network access is needed to reach your backend.",
                        Toast.LENGTH_LONG).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        auth = new Auth(this);
        // Rebuild the home screen only after validating the saved session.
        if (savedInstanceState != null) {
            getSupportFragmentManager().getFragments().forEach(fragment ->
                    getSupportFragmentManager().beginTransaction().remove(fragment).commitNow());
        }
        setContentView(R.layout.fragment_login);
        View mainLayout = findViewById(R.id.login);
        if (savedInstanceState != null) {
            screen = savedInstanceState.getInt("screen", 0);
            if (screen == 3) screen = 1;
        }
        showScreen(screen);
        findViewById(R.id.sign_in).setOnClickListener(view -> {
            if (screen == 0) showScreen(1);
            else submit();
        });
        findViewById(R.id.sign_up).setOnClickListener(view ->
                showScreen(screen == 2 ? 1 : 2));
        findViewById(R.id.forgot).setOnClickListener(view ->
                Toast.makeText(this, R.string.forgot_placeholder, Toast.LENGTH_SHORT).show());
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
            auth.restore((user, error) -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                if (user != null) {
                    accountUsername = user.optString("username");
                    loggedOn = user.optLong("last_logged_on", 0);
                    showLibrary(user.optString("name"));
                } else {
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
        if (android.os.Build.VERSION.SDK_INT >= 37
                && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK");
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

    public void signOut() {
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
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(findViewById(R.id.login).getWindowToken(), 0);
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
        openTab(R.id.vault);
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
        for (int buttonId : new int[]{R.id.vault, R.id.create, R.id.settings, R.id.web}) {
            android.widget.ImageButton button = findViewById(buttonId);
            button.setImageTintList(ColorStateList.valueOf(
                    Color.parseColor(buttonId == id ? "#306BF3" : "#626873")));
            button.setSelected(buttonId == id);
        }
        int[] buttons = {R.id.vault, R.id.create, R.id.settings, R.id.web};
        int[] labels = {R.id.vault_label, R.id.create_label, R.id.settings_label, R.id.web_label};
        for (int i = 0; i < buttons.length; i++) {
            ((TextView) findViewById(labels[i])).setTextColor(
                    Color.parseColor(buttons[i] == id ? "#306BF3" : "#626873"));
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
        findViewById(R.id.forgot).setVisibility(screen == 1 ? View.VISIBLE : View.GONE);

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

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("screen", screen);
    }

    @Override
    protected void onDestroy() {
        auth.close();
        super.onDestroy();
    }
}
