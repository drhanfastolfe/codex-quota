package es.codexquota.mobile.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

public final class TokenStore implements LoginPersistence {
    private static final String KEY_ALIAS = "es.codexquota.mobile.tokens.v1";
    private final SharedPreferences preferences;
    private static final Object LOCK = new Object();
    private static final byte[] ASSOCIATED_DATA = "codex-quota-tokens-v1".getBytes(StandardCharsets.UTF_8);

    public TokenStore(Context context) {
        this.preferences = context.getApplicationContext().getSharedPreferences("secure_tokens", 0);
    }

    public boolean isSignedIn() {
        try {
            return load() != null;
        } catch (ApiException unused) {
            return false;
        }
    }

    public String accountId() throws ApiException {
        TokenBundle tokenBundleLoad = load();
        if (tokenBundleLoad == null) {
            return null;
        }
        return tokenBundleLoad.accountId;
    }

    public String sessionIdentity() throws ApiException {
        TokenBundle tokenBundleLoad = load();
        if (tokenBundleLoad == null || tokenBundleLoad.userId == null || tokenBundleLoad.accountId == null) {
            return null;
        }
        return tokenBundleLoad.userId.length() + ":" + tokenBundleLoad.userId + ":" + tokenBundleLoad.accountId;
    }

    public TokenBundle load() throws ApiException {
        synchronized (LOCK) {
            String string = this.preferences.getString("ciphertext", null);
            if (string == null) {
                return null;
            }
            try {
                return TokenBundle.fromJson(decrypt(string));
            } catch (Exception unused) {
                clear();
                throw new ApiException("No se pudo abrir la sesión guardada. Inicia sesión otra vez.", true, 401);
            }
        }
    }

    public void save(TokenBundle tokenBundle) throws ApiException {
        synchronized (LOCK) {
            write(tokenBundle);
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public long generation() {
        long j;
        synchronized (LOCK) {
            j = this.preferences.getLong("generation", 0L);
        }
        return j;
    }

    boolean saveIfGeneration(TokenBundle tokenBundle, long j) throws ApiException {
        synchronized (LOCK) {
            if (generation() != j) {
                return false;
            }
            write(tokenBundle);
            return true;
        }
    }

    boolean replaceIfUnchanged(TokenBundle tokenBundle, TokenBundle tokenBundle2) throws ApiException {
        synchronized (LOCK) {
            if (!tokenBundle.sameCredentials(load())) {
                return false;
            }
            write(tokenBundle2);
            return true;
        }
    }

    void clearIfUnchanged(TokenBundle tokenBundle) throws ApiException {
        synchronized (LOCK) {
            if (tokenBundle.sameCredentials(load())) {
                clear();
            }
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public void savePendingChallenge(DeviceLogin.Challenge challenge) throws ApiException {
        synchronized (LOCK) {
            try {
                if (!this.preferences.edit().putString("pending_login", encrypt(challenge.toJson())).commit()) {
                    throw new IllegalStateException();
                }
            } catch (Exception unused) {
                throw new ApiException("No se pudo guardar el inicio de sesión en el móvil.", false, 0);
            }
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public DeviceLogin.Challenge loadPendingChallenge() {
        synchronized (LOCK) {
            String string = this.preferences.getString("pending_login", null);
            if (string == null) {
                return null;
            }
            try {
                DeviceLogin.Challenge challengeFromJson = DeviceLogin.Challenge.fromJson(decrypt(string));
                if (challengeFromJson.expiresAtMillis > System.currentTimeMillis() && challengeFromJson.storeGeneration == generation()) {
                    if (challengeFromJson.exchangeStarted) {
                        return null;
                    }
                    return challengeFromJson;
                }
                clearPendingChallenge();
                return null;
            } catch (Exception unused) {
                clearPendingChallenge();
                return null;
            }
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public boolean completeLogin(TokenBundle tokenBundle, DeviceLogin.Challenge challenge) throws ApiException {
        synchronized (LOCK) {
            try {
                try {
                    DeviceLogin.Challenge challengeFromJson = null;
                    String string = this.preferences.getString("pending_login", null);
                    if (string != null) {
                        challengeFromJson = DeviceLogin.Challenge.fromJson(decrypt(string));
                    }
                    if (challengeFromJson == null || !challengeFromJson.deviceAuthId.equals(challenge.deviceAuthId) || generation() != challenge.storeGeneration) {
                        return false;
                    }
                    try {
                        if (this.preferences.edit().putString("ciphertext", encrypt(tokenBundle.toJson())).putLong("generation", generation() + 1).remove("pending_login").commit()) {
                            return true;
                        }
                        throw new IllegalStateException();
                    } catch (Exception unused) {
                        throw new ApiException("No se pudo guardar la sesión de forma segura en este móvil.", false, 0);
                    }
                } catch (Exception unused2) {
                    return false;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public boolean markExchangeStarted(DeviceLogin.Challenge challenge) throws ApiException {
        synchronized (LOCK) {
            DeviceLogin.Challenge challengeLoadPendingChallenge = loadPendingChallenge();
            if (challengeLoadPendingChallenge != null && challengeLoadPendingChallenge.deviceAuthId.equals(challenge.deviceAuthId)) {
                challenge.exchangeStarted = true;
                savePendingChallenge(challenge);
                return true;
            }
            return false;
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public boolean resetUnsentExchange(DeviceLogin.Challenge challenge) throws ApiException {
        synchronized (LOCK) {
            try {
                try {
                    DeviceLogin.Challenge challengeFromJson = null;
                    String string = this.preferences.getString("pending_login", null);
                    if (string != null) {
                        challengeFromJson = DeviceLogin.Challenge.fromJson(decrypt(string));
                    }
                    if (challengeFromJson != null && challengeFromJson.deviceAuthId.equals(challenge.deviceAuthId) && generation() == challenge.storeGeneration) {
                        challenge.exchangeStarted = false;
                        savePendingChallenge(challenge);
                        return true;
                    }
                    return false;
                } catch (ApiException e) {
                    throw e;
                } catch (Exception unused) {
                    return false;
                }
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    @Override // es.codexquota.mobile.core.LoginPersistence
    public void clearPendingChallenge() {
        synchronized (LOCK) {
            this.preferences.edit().remove("pending_login").commit();
        }
    }

    /** Cancel a just-completed login without clearing a newer session. */
    public void clearIfGeneration(long expectedGeneration) {
        synchronized (LOCK) {
            if (generation() == expectedGeneration) clear();
        }
    }

    public void clear() {
        synchronized (LOCK) {
            if (!this.preferences.edit().remove("ciphertext").remove("pending_login").putLong("generation", generation() + 1).commit()) {
                try {
                    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                    keyStore.load(null);
                    keyStore.deleteEntry(KEY_ALIAS);
                } catch (Exception unused) {
                }
            }
        }
    }

    private void write(TokenBundle tokenBundle) throws ApiException {
        try {
            if (this.preferences.edit().putString("ciphertext", encrypt(tokenBundle.toJson())).commit()) {
            } else {
                throw new IllegalStateException();
            }
        } catch (Exception unused) {
            throw new ApiException("No se pudo guardar la sesión de forma segura en este móvil.", false, 0);
        }
    }

    private static String encrypt(JSONObject jSONObject) throws Exception {
        byte[] bytes = jSONObject.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(1, key());
            cipher.updateAAD(ASSOCIATED_DATA);
            byte[] bArrDoFinal = cipher.doFinal(bytes);
            byte[] iv = cipher.getIV();
            byte[] bArr = new byte[iv.length + bArrDoFinal.length];
            System.arraycopy(iv, 0, bArr, 0, iv.length);
            System.arraycopy(bArrDoFinal, 0, bArr, iv.length, bArrDoFinal.length);
            return Base64.encodeToString(bArr, 2);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static JSONObject decrypt(String str) throws Exception {
        byte[] bArrDecode = Base64.decode(str, 2);
        if (bArrDecode.length < 29) {
            throw new IllegalArgumentException();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(2, key(), new GCMParameterSpec(128, Arrays.copyOfRange(bArrDecode, 0, 12)));
        cipher.updateAAD(ASSOCIATED_DATA);
        byte[] bArrDoFinal = cipher.doFinal(Arrays.copyOfRange(bArrDecode, 12, bArrDecode.length));
        try {
            return new JSONObject(new String(bArrDoFinal, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(bArrDoFinal, (byte) 0);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            keyGenerator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
            return keyGenerator.generateKey();
        }
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }
}
