package com.example.conditions;

import androidx.fragment.app.Fragment;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class SettingsFragment extends Fragment {

    public SettingsFragment() {
        super(R.layout.fragment_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.findViewById(R.id.sign_out).setOnClickListener(button -> {
            button.setEnabled(false);
            ((MainActivity) requireActivity()).signOut();
        });
        Bundle user = getArguments();
        ((TextView) view.findViewById(R.id.settings_name)).setText(
                getString(R.string.settings_name, user == null ? "" : user.getString("name", "")));
        ((TextView) view.findViewById(R.id.settings_username)).setText(
                getString(R.string.settings_username,
                        user == null ? "" : user.getString("username", "")));
        long loggedOn = user == null ? 0 : user.getLong("logged_on");
        SimpleDateFormat format = new SimpleDateFormat("MM/dd/yy h:mm a", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        ((TextView) view.findViewById(R.id.last_login)).setText(
                getString(R.string.last_login,
                        loggedOn == 0 ? "—" : format.format(new Date(loggedOn))));
        view.findViewById(R.id.last_login).setVisibility(loggedOn == 0 ? View.GONE : View.VISIBLE);
    }
}
