package com.example.xmu_sports_booking.capture;

import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the owner's normal v58 protocol. Secrets must never be returned to Flutter or logs. */
public final class SportsProtocol {
  public static final String HOST = "xdty.xmu.edu.cn";
  public static final String PREFIX = "/bdlp_h5_fitness_test/public/index.php";
  private static final String HOME = "/bdlp_h5_fitness_test/view/stadium/home.html";
  private static final String CHOOSE = "/bdlp_h5_fitness_test/view/stadium/choose.html";
  private static final String SUFFIX =
      "rDJiNB9j7vD2"; // Public v58 client profile, not a user credential.
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Rejection details contain only a fixed stage/status and a redacted school explanation. */
  public static final class ReadRejectedException extends Exception {
    public final String stage, schoolMessage;
    public final int status;

    ReadRejectedException(String code, String stage, int status, String schoolMessage) {
      super(code);
      this.stage = stage;
      this.status = status;
      this.schoolMessage = schoolMessage;
    }

    public String description() {
      return "学校授权校验未通过（步骤：" + stage + "，状态：" + status + "）：" + schoolMessage
          + "。请在微信重新进入学校体育预约页面，再到本机设置更新学校授权后查单；待核对记录会保留。";
    }
  }

  static void checkResponse(String path, JSONObject response, String requestBody) throws Exception {
    if (response.optInt("status", -1) == 1) return;
    String code, stage;
    if (path.equals("/v3/api.php/WpLogin/UserInfo")) {
      code = "READ_CHECK_IDENTITY_REJECTED";
      stage = "核对本人身份";
    } else if (path.equals(PREFIX + "/index/index/checklogin")) {
      code = "READ_CHECK_LOGIN_REJECTED";
      stage = "建立体育会话";
    } else {
      code = "READ_CHECK_ORDERS_REJECTED";
      stage = "读取本人订单";
    }
    String message = "";
    for (String name : new String[] {"info", "msg", "message"}) {
      Object value = response.opt(name);
      if (value instanceof String && !((String) value).isBlank()) {
        message = (String) value;
        break;
      }
    }
    message = message.replaceAll("<[^>]*>", " ").replaceAll("[\\p{Cntrl}]", " ")
        .replaceAll("https?://\\S+", "[链接已隐藏]");
    for (String item : requestBody.split("&")) {
      String[] pair = item.split("=", 2);
      if (pair.length != 2) continue;
      String value = URLDecoder.decode(pair[1], "UTF-8");
      if (value.length() >= 3) message = message.replace(value, "[已隐藏]");
      if (pair[1].length() >= 3) message = message.replace(pair[1], "[已隐藏]");
    }
    message = message.replaceAll(
        "(?i)(token|sign|cookie|phpsessid|student_num|card_id|uid)\\s*[:=]\\s*[^\\s,;，；]+", "$1=[已隐藏]")
        .replaceAll("[0-9]{5,}", "[号码已隐藏]").trim();
    if (message.isEmpty()) message = "学校未提供详细原因";
    throw new ReadRejectedException(code, stage, response.optInt("status", -1),
        message.substring(0, Math.min(message.length(), 160)));
  }

  public static boolean sportsPage(String raw) {
    try {
      URI url = new URI(raw);
      return "https".equals(url.getScheme())
          && HOST.equals(url.getHost())
          && (url.getPort() == -1 || url.getPort() == 443)
          && url.getUserInfo() == null
          && url.getFragment() == null
          && (HOME.equals(url.getPath()) || CHOOSE.equals(url.getPath()));
    } catch (Exception ignored) {
      return false;
    }
  }

  public static TreeMap<String, String> parse(String query, String expected) throws Exception {
    if (query == null || query.length() > 16384 || !expected.matches("[0-9]{6,24}"))
      throw new Exception("INVALID_CONTEXT");
    TreeMap<String, String> fields = new TreeMap<>();
    for (String item : query.split("&", -1)) {
      String[] pair = item.split("=", 2);
      if (pair.length != 2) throw new Exception("INVALID_CONTEXT");
      String name = URLDecoder.decode(pair[0], "UTF-8"),
          value = URLDecoder.decode(pair[1], "UTF-8");
      if (!java.util.Set.of(
                  "timestamp",
                  "nonce",
                  "course_id",
                  "uid",
                  "card_id",
                  "login_type",
                  "type",
                  "school_id",
                  "student_num",
                  "user_type",
                  "token",
                  "sign",
                  "term_id",
                  "id")
              .contains(name)
          || fields.put(name, value) != null
          || value.length() > 4096) throw new Exception("INVALID_CONTEXT");
    }
    fields.remove("term_id");
    fields.remove("id");
    if (fields.size() != 12 || fields.values().stream().anyMatch(String::isEmpty))
      throw new Exception("INVALID_CONTEXT");
    if (!expected.equals(fields.get("student_num")) || !expected.equals(fields.get("card_id")))
      throw new Exception("OWNER_MISMATCH");
    if (!"4".equals(fields.get("login_type")) || !"1".equals(fields.get("type")))
      throw new Exception("NON_NATIVE_CONTEXT");
    String signature = fields.remove("sign");
    if (!sign(fields).equals(signature)) throw new Exception("INVALID_SIGNATURE");
    fields.put("sign", signature);
    return fields;
  }

  public static String sign(Map<String, ?> fields) throws Exception {
    StringBuilder canonical = new StringBuilder();
    for (Map.Entry<String, ?> entry : new TreeMap<>(fields).entrySet())
      canonical.append(entry.getKey()).append(entry.getValue());
    canonical.append(SUFFIX);
    byte[] digest =
        MessageDigest.getInstance("MD5")
            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return hex.toString();
  }

  private static String encode(Map<String, ?> fields) throws Exception {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, ?> entry : fields.entrySet()) {
      if (body.length() > 0) body.append('&');
      body.append(URLEncoder.encode(entry.getKey(), "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(entry.getValue().toString(), "UTF-8"));
    }
    return body.toString();
  }

  private static String nonce() {
    return String.format(java.util.Locale.ROOT, "%06d", RANDOM.nextInt(1000000));
  }

  private static final class Response {
    JSONObject json;
    String session;
  }

  private static Response post(String path, String body, String referer, String session)
      throws Exception {
    // All destinations are constants owned by this implementation; callers cannot supply a URL.
    HttpURLConnection connection =
        (HttpURLConnection) new URL("https://" + HOST + path).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(8888);
    connection.setReadTimeout(8888);
    connection.setRequestMethod("POST");
    connection.setDoOutput(true);
    connection.setRequestProperty(
        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
    connection.setRequestProperty("Origin", "https://" + HOST);
    connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            + " MicroMessenger/8.0.79");
    if (referer != null) connection.setRequestProperty("Referer", referer);
    if (session != null) connection.setRequestProperty("Cookie", "PHPSESSID=" + session);
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(bytes.length);
      try (java.io.OutputStream out = connection.getOutputStream()) {
        out.write(bytes);
      }
      if (connection.getResponseCode() != 200) throw new Exception("READ_CHECK_HTTP_FAILED");
      ByteArrayOutputStream data = new ByteArrayOutputStream();
      try (java.io.InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (data.size() + count > 2_000_000) throw new Exception("READ_CHECK_TOO_LARGE");
          data.write(buffer, 0, count);
        }
      }
      Response result = new Response();
      result.json = new JSONObject(data.toString("UTF-8"));
      checkResponse(path, result.json, body);
      for (Map.Entry<String, java.util.List<String>> header :
          connection.getHeaderFields().entrySet()) {
        if (!path.equals(PREFIX + "/index/index/checklogin")) continue;
        if (!"set-cookie".equalsIgnoreCase(header.getKey())) continue;
        for (String raw : header.getValue()) {
          if (!raw.startsWith("PHPSESSID=")) continue;
          for (java.net.HttpCookie cookie : java.net.HttpCookie.parse(raw)) {
            if ("PHPSESSID".equals(cookie.getName())
                && cookie.getValue().matches("[A-Za-z0-9,._%-]{1,256}")
                && !cookie.hasExpired()
                && (cookie.getDomain() == null
                    || HOST.equals(cookie.getDomain())
                    || ("." + HOST).equals(cookie.getDomain()))) {
              String scope = cookie.getPath();
              if (scope == null || !scope.startsWith("/"))
                scope = path.substring(0, path.lastIndexOf('/'));
              String ordersPath = PREFIX + "/index/stadium/mySubscribe";
              if (!(ordersPath.equals(scope)
                  || ordersPath.startsWith(scope.endsWith("/") ? scope : scope + "/"))) continue;
              result.session = cookie.getValue();
            }
          }
        }
      }
      return result;
    } finally {
      connection.disconnect();
    }
  }

  /** Fresh authenticated UserInfo, one clean checkLogin, one own-orders read. No booking routes. */
  public static JSONObject verifyAndRecord(
      String query, String expected, java.util.function.Consumer<String> progress)
      throws Exception {
    return verifySession(query, expected, progress).record;
  }

  /** Session material stays in process memory; it is never included in the saved record. */
  public static final class VerifiedSession {
    public final JSONObject record;
    public final String session;
    public final JSONArray firstOrders;
    private VerifiedSession(JSONObject record, String session, JSONArray firstOrders) {
      this.record = record; this.session = session; this.firstOrders = firstOrders;
    }
  }

  public static VerifiedSession verifySession(
      String query, String expected, java.util.function.Consumer<String> progress)
      throws Exception {
    TreeMap<String, String> fields = parse(query, expected);
    TreeMap<String, Object> identityRequest = new TreeMap<>();
    for (String key : new String[] {"uid", "token", "student_num", "card_id", "school_id"})
      identityRequest.put(key, fields.get(key));
    identityRequest.put("term_id", 0);
    identityRequest.put("course_id", 0);
    identityRequest.put("class_id", 0);
    identityRequest.put("timestamp", System.currentTimeMillis() / 1000);
    identityRequest.put("version", 1);
    identityRequest.put("nonce", nonce());
    identityRequest.put("ostype", 5);
    identityRequest.put("sign", sign(identityRequest));
    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
    SecretKeySpec key =
        new SecretKeySpec("Wet2C8d34f62ndi3".getBytes(StandardCharsets.UTF_8), "AES");
    IvParameterSpec iv = new IvParameterSpec("K6iv85jBD8jgf32D".getBytes(StandardCharsets.UTF_8));
    cipher.init(Cipher.ENCRYPT_MODE, key, iv);
    String encoded =
        android.util.Base64.encodeToString(
            cipher.doFinal(
                new JSONObject(identityRequest).toString().getBytes(StandardCharsets.UTF_8)),
            android.util.Base64.NO_WRAP);
    progress.accept("CHECKING_OWN_IDENTITY");
    Response identity =
        post(
            "/v3/api.php/WpLogin/UserInfo",
            encode(Map.of("ostype", "5", "data", encoded)),
            null,
            null);
    progress.accept("DECODING_OWN_IDENTITY");
    cipher.init(Cipher.DECRYPT_MODE, key, iv);
    JSONObject me =
        new JSONObject(
            new String(
                cipher.doFinal(
                    android.util.Base64.decode(
                        identity.json.getString("data"), android.util.Base64.DEFAULT)),
                StandardCharsets.UTF_8));
    if (!expected.equals(me.optString("student_num"))
        || !fields.get("uid").equals(me.optString("uid"))
        || !fields.get("school_id").equals(me.optString("school_id")))
      throw new Exception("OWNER_MISMATCH");
    int role = me.optInt("role", -1);
    String userType = role == 1 ? "2" : role == 2 ? "1" : role == 3 ? "3" : "";
    if (!userType.equals(fields.get("user_type"))) throw new Exception("IDENTITY_CONTEXT_MISMATCH");
    fields.remove("sign");
    fields.put("timestamp", Long.toString(System.currentTimeMillis() / 1000));
    fields.put("nonce", nonce());
    fields.put("sign", sign(fields));
    String referer = "https://" + HOST + HOME + "?" + encode(fields);
    TreeMap<String, String> body = new TreeMap<>(fields);
    body.put("term_id", "");
    body.put("id", "");
    progress.accept("CREATING_SPORTS_SESSION");
    Response entry = post(PREFIX + "/index/index/checklogin", encode(body), referer, null);
    if (entry.session == null) throw new Exception("SCOPED_SESSION_MISSING");
    progress.accept("READING_OWN_ORDERS");
    Response orders = post(PREFIX + "/index/stadium/mySubscribe", "p=1", referer, entry.session);
    JSONArray rows = orders.json.optJSONArray("data");
    if (rows == null) throw new Exception("ORDERS_SHAPE_INVALID");
    for (int i = 0; i < rows.length(); i++)
      if (!fields.get("uid").equals(rows.getJSONObject(i).optString("uid")))
        throw new Exception("ORDERS_OWNER_MISMATCH");
    JSONObject record = new JSONObject();
    record.put("fields", new JSONObject(fields));
    record.put("verified_at", System.currentTimeMillis());
    record.put("orders_count", rows.length());
    record.put("source", "OWN_WECHAT_WEBVIEW");
    return new VerifiedSession(record, entry.session, rows);
  }
}
