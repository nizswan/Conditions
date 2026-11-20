package com.example.conditions;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Talks to the local backend through ADB port forwarding. */
public class Auth {
    private static final String SERVER = "http://127.0.0.1:8000";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String token;
    private JSONObject savedUser;
    private volatile boolean signedOut;
    private final SessionStore store;
    private final android.content.Context context;

    public Auth(android.content.Context context) {
        this.context = context.getApplicationContext();
        store = new SessionStore(this.context);
        String saved = store.read();
        if (saved != null && saved.startsWith("{")) {
            try {
                JSONObject session = new JSONObject(saved);
                token = session.getString("token");
                savedUser = session.getJSONObject("user");
            } catch (org.json.JSONException error) {
                store.clear();
                token = null;
            }
        } else {
            // Older installs saved just a token. /me will fill in the account details.
            token = saved;
        }
    }

    public JSONObject getSavedUser() {
        return savedUser;
    }

    private synchronized void saveSession(String sessionToken, JSONObject user) throws Exception {
        if (signedOut) return;
        if (savedUser != null && savedUser.optLong("id") == user.optLong("id")) {
            String oldName = savedUser.optString("username");
            String newName = user.optString("username");
            if (!oldName.equals(newName)) {
                android.content.SharedPreferences oldSettings = context.getSharedPreferences("conditions_" + oldName, 0);
                android.content.SharedPreferences.Editor editor = context.getSharedPreferences("conditions_" + newName, 0).edit().clear();
                for (java.util.Map.Entry<String, ?> entry : oldSettings.getAll().entrySet()) {
                    if (entry.getValue() instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                }
                editor.commit();
                oldSettings.edit().clear().commit();
                android.content.ContentValues values = new android.content.ContentValues();
                values.put("username", newName);
                try (HistoryStore history = new HistoryStore(context); LibraryStore libraries = new LibraryStore(context)) {
                    history.getWritableDatabase().update("visits", values, "username = ?", new String[]{oldName});
                    libraries.getWritableDatabase().update("libraries", values, "username = ?", new String[]{oldName});
                    libraries.getWritableDatabase().update("blocks", values, "username = ?", new String[]{oldName});
                    libraries.getWritableDatabase().update("choices", values, "username = ?", new String[]{oldName});
                }
            }
        }
        JSONObject session = new JSONObject();
        session.put("token", sessionToken);
        session.put("user", user);
        store.save(session.toString());
        token = sessionToken;
        savedUser = user;
    }

    public boolean hasSession() {
        return token != null;
    }

    public void restore(Result result) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            JSONObject user = null;
            String error = null;
            try {
                connection = (HttpURLConnection) new URL(SERVER + "/me").openConnection();
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                int status = connection.getResponseCode();
                if (status == 401) {
                    token = null;
                    savedUser = null;
                    store.clear();
                    error = "Your session expired. Please sign in again.";
                } else if (status == 200) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(connection.getInputStream(),
                                    StandardCharsets.UTF_8))) {
                        StringBuilder body = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                        user = new JSONObject(body.toString()).getJSONObject("user");
                        saveSession(token, user);
                    }
                } else {
                    error = "Cannot verify your session. Please try again when the backend is running.";
                }
            } catch (Exception exception) {
                error = "Cannot verify your session. Please try again when the backend is running.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            // A network failure does not mean the user signed out.
            if (user == null && savedUser != null) {
                user = savedUser;
                error = null;
            }
            JSONObject finalUser = user;
            String finalError = error;
            main.post(() -> {
                if (!signedOut) result.complete(finalUser, finalError);
            });
        });
    }

    public interface Result {
        void complete(JSONObject user, String error);
    }

    public void submit(boolean signingUp, String name, String username,
                       String password, Result result) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            JSONObject user = null;
            String error = null;
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("username", username);
                body.put("password", password);
                connection = (HttpURLConnection) new URL(
                        SERVER + (signingUp ? "/signup" : "/signin")).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                InputStream stream = status < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) throw new java.io.IOException("Empty response");
                StringBuilder response = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
                JSONObject data = new JSONObject(response.toString());
                if (status < 400) {
                    JSONObject account = data.getJSONObject("user");
                    saveSession(data.getString("token"), account);
                    user = account;
                } else {
                    error = data.optString("error", "Please try again.");
                }
            } catch (Exception exception) {
                android.util.Log.w("ConditionsAuth",
                        "Authentication request failed: " + exception.getClass().getSimpleName()
                                + (exception instanceof java.io.IOException
                                ? ": " + exception.getMessage() : ""));
                if (exception instanceof org.json.JSONException) {
                    error = "The backend returned an unexpected response.";
                } else {
                    error = "Cannot reach the development server. Check the backend and run backend/connect-device.ps1.";
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONObject finalUser = user;
            String finalError = error;
            main.post(() -> result.complete(finalUser, finalError));
        });
    }

    public void updateProfile(String field, String value, Result result) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            JSONObject user = null;
            String error = null;
            try {
                JSONObject body = new JSONObject();
                body.put("field", field);
                body.put("value", value);
                connection = (HttpURLConnection) new URL(
                        SERVER + "/profile").openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                InputStream stream = status < 400
                        ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) throw new java.io.IOException("Empty response");
                StringBuilder response = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) response.append(line);
                }
                JSONObject data = new JSONObject(response.toString());
                if (status < 400) {
                    JSONObject account = data.getJSONObject("user");
                    saveSession(token, account);
                    user = account;
                } else {
                    error = data.optString("error", "Please try again.");
                }
            } catch (Exception exception) {
                android.util.Log.w("ConditionsAuth",
                        "Authentication request failed: " + exception.getClass().getSimpleName()
                                + (exception instanceof java.io.IOException
                                ? ": " + exception.getMessage() : ""));
                if (exception instanceof org.json.JSONException) {
                    error = "The backend returned an unexpected response.";
                } else {
                    error = "Cannot reach the development server. Check the backend and run backend/connect-device.ps1.";
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONObject finalUser = user;
            String finalError = error;
            main.post(() -> result.complete(finalUser, finalError));
        });
    }

    public void deleteAccount(Result result) {
        worker.execute(() -> {
            HttpURLConnection connection = null;
            String error = null;
            try {
                connection = (HttpURLConnection) new URL(SERVER + "/account").openConnection();
                connection.setRequestMethod("DELETE");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                int status = connection.getResponseCode();
                if (status != 200) error = status == 401 ? "Please sign in again before deleting your account."
                        : "Could not delete your account. Please try again.";
            } catch (Exception exception) {
                error = "Could not confirm account deletion. Check your connection and try again.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            String finalError = error;
            main.post(() -> result.complete(null, finalError));
        });
    }

    public String getToken() {
        return token;
    }

    public synchronized void signOut(Runnable finished) {
        signedOut = true;
        String oldToken = token;
        token = null;
        savedUser = null;
        store.clear();
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(SERVER + "/signout").openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setRequestProperty("Authorization", "Bearer " + oldToken);
                connection.getResponseCode();
            } catch (java.io.IOException ignored) {
                // Still sign out locally if the backend is offline.
            } finally {
                if (connection != null) connection.disconnect();
                main.post(finished);
            }
        });
    }

    public void close() {
        worker.shutdownNow();
    }
}
