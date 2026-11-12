package com.example.xmu_sports_booking.capture;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** App-private AES-GCM storage with a non-exportable Android Keystore key. */
public final class TokenVault {
  private final Context context;

  public TokenVault(Context context) {
    this.context = context.getApplicationContext();
  }

  private SecretKey key() throws Exception {
    String alias = context.getPackageName() + ".own-sports-token-v1";
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (!store.containsAlias(alias)) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) store.getKey(alias, null);
  }

  public void save(JSONObject record) throws Exception {
    synchronized (TokenVault.class) {
      android.content.SharedPreferences prefs =
          context.getSharedPreferences("own_sports_token", Context.MODE_PRIVATE);
      long version = prefs.getLong("authorization_version", 0) + 1;
      if (!prefs.edit().putLong("authorization_version", version).commit())
        throw new Exception("SAVE_FAILED");
      saveSecret(
          "sealed",
          new JSONObject(record.toString()).put("authorization_version", version).toString());
    }
  }

  /** A delayed read-only verification must never restore deleted/replaced authorization. */
  public boolean replaceRecordIfCurrent(String expected, JSONObject record) throws Exception {
    synchronized (TokenVault.class) {
      String current = loadSecret("sealed");
      if (current == null || expected == null) return false;
      JSONObject before = new JSONObject(expected), now = new JSONObject(current);
      if (before.optLong("authorization_version", 0) != now.optLong("authorization_version", 0))
        return false;
      java.util.TreeMap<String, String> first =
          SportsProtocol.parse(
              encodeFields(before), before.getJSONObject("fields").getString("student_num"));
      java.util.TreeMap<String, String> second =
          SportsProtocol.parse(
              encodeFields(now), now.getJSONObject("fields").getString("student_num"));
      for (String key : new String[] {"timestamp", "nonce", "sign"}) {
        first.remove(key);
        second.remove(key);
      }
      if (!first.equals(second)) return false;
      saveSecret(
          "sealed",
          new JSONObject(record.toString())
              .put("authorization_version", now.optLong("authorization_version", 0))
              .toString());
      return true;
    }
  }

  private static String encodeFields(JSONObject record) throws Exception {
    JSONObject f = record.getJSONObject("fields");
    java.util.TreeMap<String, String> fields = new java.util.TreeMap<>();
    java.util.Iterator<String> keys = f.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      fields.put(key, f.getString(key));
    }
    StringBuilder out = new StringBuilder();
    for (java.util.Map.Entry<String, String> e : fields.entrySet()) {
      if (out.length() > 0) out.append('&');
      out.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"))
          .append('=')
          .append(java.net.URLEncoder.encode(e.getValue(), "UTF-8"));
    }
    return out.toString();
  }

  public void saveSecret(String name, String plaintext) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    cipher.updateAAD((context.getPackageName() + ":" + name).getBytes(StandardCharsets.UTF_8));
    String blob =
        Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
            + "."
            + Base64.encodeToString(
                cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    if (!context
        .getSharedPreferences("own_sports_token", Context.MODE_PRIVATE)
        .edit()
        .putString(name, blob)
        .commit()) throw new Exception("SAVE_FAILED");
  }

  public void clear() throws Exception {
    synchronized (TokenVault.class) {
      android.content.SharedPreferences prefs =
          context.getSharedPreferences("own_sports_token", Context.MODE_PRIVATE);
      if (!prefs
          .edit()
          .putLong("authorization_version", prefs.getLong("authorization_version", 0) + 1)
          .remove("sealed")
          .remove("expected_student")
          .commit()) throw new Exception("CLEAR_FAILED");
    }
  }

  public String loadSecret(String name) throws Exception {
    String sealed =
        context
            .getSharedPreferences("own_sports_token", Context.MODE_PRIVATE)
            .getString(name, null);
    if (sealed == null) return null;
    String[] parts = sealed.split("\\.", 2);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
    cipher.updateAAD((context.getPackageName() + ":" + name).getBytes(StandardCharsets.UTF_8));
    return new String(
        cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
  }

  public JSONObject metadata() throws Exception {
    String plaintext = loadSecret("sealed");
    if (plaintext == null) return new JSONObject().put("has_token", false);
    JSONObject record = new JSONObject(plaintext);
    String owner = record.getJSONObject("fields").getString("student_num");
    return new JSONObject()
        .put("has_token", true)
        .put("owner", owner.substring(0, 2) + "****" + owner.substring(owner.length() - 2))
        .put("verified_at", record.getLong("verified_at"))
        .put("orders_count", record.getInt("orders_count"))
        .put("source", "OWN_WECHAT_WEBVIEW");
  }
}
