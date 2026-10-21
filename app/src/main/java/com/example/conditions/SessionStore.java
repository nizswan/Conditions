package com.example.conditions;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

/** Saves only the session token, encrypted with a device-held key, outside backups. */
final class SessionStore {
    private static final String KEY = "conditions_session";
    private final File file;

    SessionStore(Context context) {
        file = new File(context.getNoBackupFilesDir(), "session");
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(KEY, null);
    }

    String read() {
        try {
            byte[] bytes;
            try (java.io.FileInputStream input = new android.util.AtomicFile(file).openRead();
                 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                byte[] chunk = new byte[256];
                int count;
                while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
                bytes = buffer.toByteArray();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(
                    128, java.util.Arrays.copyOfRange(bytes, 0, 12)));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            clear();
            return null;
        }
    }

    void save(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = new byte[12 + encrypted.length];
        System.arraycopy(cipher.getIV(), 0, bytes, 0, 12);
        System.arraycopy(encrypted, 0, bytes, 12, encrypted.length);
        // AtomicFile avoids a partially written token after a crash.
        android.util.AtomicFile atomic = new android.util.AtomicFile(file);
        java.io.FileOutputStream output = atomic.startWrite();
        try {
            output.write(bytes);
            atomic.finishWrite(output);
        } catch (Exception error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    void clear() {
        new android.util.AtomicFile(file).delete();
    }
}
