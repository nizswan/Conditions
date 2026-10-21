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

/** Talks to the local backend from an Android emulator. */
public class Auth {
    private static final String SERVER = "http://10.0.2.2:8000";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private String token;
    private final SessionStore store;

    public Auth(android.content.Context context) {
        store = new SessionStore(context.getApplicationContext());
        token = store.read();
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
                    }
                } else {
                    error = "Cannot verify your session. Please try again when the backend is running.";
                }
            } catch (Exception exception) {
                error = "Cannot verify your session. Please try again when the backend is running.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONObject finalUser = user;
            String finalError = error;
            main.post(() -> result.complete(finalUser, finalError));
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
                    token = data.getString("token");
                    store.save(token);
                    user = data.getJSONObject("user");
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
                    error = "Cannot reach the backend at 10.0.2.2:8000. Check the API container.";
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONObject finalUser = user;
            String finalError = error;
            main.post(() -> result.complete(finalUser, finalError));
        });
    }

    public String getToken() {
        return token;
    }

    public void signOut(Runnable finished) {
        String oldToken = token;
        token = null;
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
