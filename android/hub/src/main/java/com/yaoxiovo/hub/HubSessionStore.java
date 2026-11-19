package com.yaoxiovo.hub;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.json.JSONObject;

final class HubSessionStore {
    private static final String ALIAS = "yaoxi-hub-session";
    private static final String FIELD = "encryptedSession";
    private static final int IV_BYTES = 12;
    private final SharedPreferences preferences;

    HubSessionStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    JSONObject load(String origin) {
        String saved = preferences.getString(FIELD, null);
        if (saved == null) return new JSONObject();
        try {
            byte[] bytes = Base64.decode(saved, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, Arrays.copyOf(bytes, IV_BYTES)));
            cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
            return new JSONObject(new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES),
                StandardCharsets.UTF_8));
        } catch (Exception error) {
            clear();
            return new JSONObject();
        }
    }

    void save(String origin, String session, String account) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            cipher.updateAAD(origin.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(HubApi.object("session", session, "account", account)
                .toString().getBytes(StandardCharsets.UTF_8));
            byte[] bytes = Arrays.copyOf(cipher.getIV(), IV_BYTES + encrypted.length);
            System.arraycopy(encrypted, 0, bytes, IV_BYTES, encrypted.length);
            preferences.edit().putString(FIELD, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply();
        } catch (Exception error) {
            clear();
        }
    }

    void clear() {
        preferences.edit().remove(FIELD).apply();
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        if (!create) throw new GeneralSecurityException("Session key missing");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }
}
