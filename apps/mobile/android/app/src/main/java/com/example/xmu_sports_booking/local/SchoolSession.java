package com.example.xmu_sports_booking.local;

import android.content.Context;
import com.example.xmu_sports_booking.capture.SportsProtocol;
import com.example.xmu_sports_booking.capture.TokenVault;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Fixed-host school transport; one session per operation, no shared/global CookieManager. */
public final class SchoolSession implements AutoCloseable {
  public static final String HOST = "xdty.xmu.edu.cn",
      PREFIX = "/bdlp_h5_fitness_test/public/index.php";
  private static final String BASE = "https://" + HOST;
  private static final Set<String> ROUTES =
      Set.of(
          "/index/index/checklogin",
          "/index/Stadium/getStadiumList",
          "/index/Stadium/getStadiumDetails",
          "/stadium/interval/getInterval",
          "/index/stadium/mySubscribe",
          "/index/stadium/orderDetails",
          "/index/Stadium/getVenueConfig",
          "/index/stadium/chooseVerify",
          "/index/Stadium/addOrder",
          "/index/index/captcha");
  private static final Set<String> TERMINAL =
      Set.of("已取消", "已退款", "已拒绝", "待支付", "预约失败", "审核不通过", "已核销", "已完成", "已结束");
  private final TreeMap<String, String> fields;
  private final Wire wire;
  private boolean submitted = false, prepared = false;
  private String cookie = "", referer;

  public interface Wire {
    Reply call(
        String route, Map<String, String> values, String cookie, String referer, boolean image)
        throws Exception;
  }

  public static final class Reply {
    public JSONObject json;
    public byte[] image;
    public String session;

    public Reply(JSONObject json) {
      this.json = json;
    }
  }

  public static SchoolSession open(Context context) throws Exception {
    TokenVault vault = new TokenVault(context);
    String saved = vault.loadSecret("sealed");
    require(saved != null, "AUTH_REQUIRED");
    JSONObject record = new JSONObject(saved), f = record.getJSONObject("fields");
    TreeMap<String, String> values = map(f);
    JSONObject fresh =
        SportsProtocol.verifyAndRecord(encode(values), values.get("student_num"), stage -> {});
    require(vault.replaceRecordIfCurrent(saved, fresh), "AUTHORIZATION_CHANGED");
    return new SchoolSession(fresh, new HttpWire());
  }

  /** Injectable transport is used only by synthetic native tests. */
  public SchoolSession(JSONObject record, Wire wire) throws Exception {
    fields =
        SportsProtocol.parse(
            encode(map(record.getJSONObject("fields"))),
            record.getJSONObject("fields").getString("student_num"));
    this.wire = wire;
    referer = BASE + "/bdlp_h5_fitness_test/view/stadium/home.html?" + encode(fields);
    TreeMap<String, String> body = new TreeMap<>(fields);
    body.put("term_id", "");
    body.put("id", "");
    call("/index/index/checklogin", body, false);
    require(!cookie.isEmpty(), "AUTH_REQUIRED");
  }

  public static void require(boolean ok, String code) throws Exception {
    if (!ok) throw new Exception(code);
  }

  public static TreeMap<String, String> map(JSONObject value) throws JSONException {
    TreeMap<String, String> result = new TreeMap<>();
    Iterator<String> it = value.keys();
    while (it.hasNext()) {
      String k = it.next();
      result.put(k, value.get(k).toString());
    }
    return result;
  }

  public static String encode(Map<String, String> values) throws Exception {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> e : values.entrySet()) {
      if (body.length() > 0) body.append('&');
      body.append(URLEncoder.encode(e.getKey(), "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(e.getValue(), "UTF-8"));
    }
    return body.toString();
  }

  private Reply call(String route, Map<String, String> values, boolean image) throws Exception {
    require(ROUTES.contains(route), "ROUTE_REFUSED");
    Reply r = wire.call(route, values, cookie, referer, image);
    if (r.session != null) {
      require(cookie.isEmpty() || cookie.equals(r.session), "SESSION_CHANGED");
      cookie = r.session;
    }
    if (!image) require(r.json != null && r.json.optInt("status", -1) == 1, "SCHOOL_REJECTED");
    return r;
  }

  public JSONObject read(String route, Map<String, String> values) throws Exception {
    require(
        !route.endsWith("addOrder")
            && !route.endsWith("chooseVerify")
            && !route.endsWith("checklogin")
            && !route.endsWith("captcha"),
        "READ_ROUTE_REFUSED");
    return call(route, values, false).json;
  }

  public JSONArray orders(boolean expand) throws Exception {
    JSONArray result = new JSONArray();
    Set<String> seen = new HashSet<>();
    boolean restarted = false;
    for (int read = 0, page = 1; read < 100; read++, page++) {
      JSONArray rows =
          read("/index/stadium/mySubscribe", Map.of("p", Integer.toString(page)))
              .getJSONArray("data");
      boolean overlap = false;
      for (int i = 0; i < rows.length(); i++) {
        JSONObject row = rows.getJSONObject(i);
        require(fields.get("uid").equals(row.optString("uid")), "OWNER_MISMATCH");
        String id = row.optString("order_id");
        require(!id.isEmpty() && id.length() <= 256, "ORDER_ID_INVALID");
        if (!seen.add(id)) {
          overlap = true;
          break;
        }
        if (expand
            && !TERMINAL.contains(row.optString("audit_status_text"))
            && (row.optJSONArray("detail") == null || row.optJSONArray("detail").length() == 0)) {
          JSONObject detail =
              read("/index/stadium/orderDetails", Map.of("order_id", id, "order_num", ""))
                  .getJSONObject("data");
          require(id.equals(detail.optString("order_id")), "ORDER_DETAILS_IDENTITY_MISMATCH");
          require(
              !detail.has("uid") || fields.get("uid").equals(detail.optString("uid")),
              "OWNER_MISMATCH");
          for (String key :
              new String[] {
                "stadium_id", "venue_id", "category_id", "stadium_name", "project_name"
              }) {
            if (detail.has(key)) {
              require(
                  !row.has(key) || row.optString(key).equals(detail.optString(key)),
                  "ORDER_METADATA_MISMATCH");
              row.put(key, detail.get(key));
            }
          }
          JSONArray details = detail.getJSONArray("details");
          require(details.length() > 0, "ORDER_DETAILS_EMPTY");
          for (int j = 0; j < details.length(); j++) {
            JSONObject item = details.getJSONObject(j);
            if (!item.has("status_text")) item.put("status_text", state(item.optInt("status", -1)));
          }
          row.put("detail", details);
        }
        if (!row.has("detail")) row.put("detail", new JSONArray());
        result.put(row);
      }
      if (overlap) {
        require(!restarted, "INCOMPLETE_ORDER_SCAN");
        result = new JSONArray();
        seen.clear();
        page = 0;
        restarted = true;
        continue;
      }
      if (rows.length() < 10) return result;
    }
    throw new Exception("INCOMPLETE_ORDER_SCAN");
  }

  public static String state(int n) {
    switch (n) {
      case 1:
        return "已预约";
      case 2:
        return "已核销";
      case 3:
        return "已取消";
      case 4:
        return "未核销";
      case 5:
        return "已拒绝";
      case 6:
        return "待支付";
      default:
        return "未知";
    }
  }

  public JSONObject inventory(JSONObject target) throws Exception {
    JSONObject details =
        read("/index/Stadium/getStadiumDetails", Map.of("id", target.getString("stadium_id")))
            .getJSONObject("data");
    require(details.getString("name").equals(target.getString("stadium_name")), "VENUE_MISMATCH");
    JSONArray venues = details.getJSONArray("venue");
    boolean found = false;
    for (int i = 0; i < venues.length(); i++) {
      JSONObject venue = venues.getJSONObject(i);
      if (!venue.optString("category_id").equals(target.getString("category_id"))
          || !venue.optString("name").equals(target.getString("project_name"))) continue;
      JSONArray list = venue.getJSONArray("list");
      for (int j = 0; j < list.length(); j++)
        if (list.getJSONObject(j).optString("venue_id").equals(target.getString("venue_id")))
          found = true;
    }
    require(found, "VENUE_MISMATCH");
    Map<String, String> values = targetFields(target);
    values.put("user_range", details.get("user_range").toString());
    return read("/stadium/interval/getInterval", values).getJSONObject("data");
  }

  public void initializeHome() throws Exception {
    // The existing reference adapter validates the data object, not a stadium_list array.
    // Displayed mappings are independently verified by getStadiumDetails and getInterval.
    read("/index/Stadium/getStadiumList", fields).getJSONObject("data");
  }

  public static Map<String, String> targetFields(JSONObject target) throws Exception {
    Map<String, String> values = new TreeMap<>();
    for (String k : new String[] {"stadium_id", "venue_id", "category_id"}) {
      String v = target.getString(k);
      require(v.matches("[1-9][0-9]{0,8}"), "INVALID_TARGET");
      values.put(k, v);
    }
    return values;
  }

  public static JSONObject select(JSONObject data, JSONObject target, String range)
      throws Exception {
    require(data.optInt("is_can_choose") == 1, "VENUE_UNAVAILABLE");
    require(data.optDouble("venue_appointment_min_num", 0) <= 1, "GROUP_UNSUPPORTED");
    double capacity = data.getDouble("personal_num");
    require(Double.isFinite(capacity) && capacity > 0, "SLOT_SHAPE");
    JSONArray rows = data.getJSONArray("time_slot_list");
    List<JSONObject> eligible = new ArrayList<>();
    for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (!target.getString("date").equals(row.optString("date"))
          || !range.equals(normalizedRange(row.optString("time_range")))) continue;
      JSONArray slots = row.optJSONArray("slots");
      if (slots == null && row.has("column_id")) slots = new JSONArray().put(row);
      require(slots != null, "SLOT_SHAPE");
      for (int j = 0; j < slots.length(); j++) {
        JSONObject slot = slots.getJSONObject(j);
        if (!target.getString("date").equals(slot.optString("date"))
            || !"available".equals(slot.optString("status"))
            || slot.getDouble("selected") >= capacity
            || slot.getDouble("price") != 0) continue;
        JSONObject detail =
            new JSONObject()
                .put("date", target.getString("date"))
                .put("week", row.get("week").toString())
                .put("week_msg", row.getString("week_name"))
                .put("area_name", slot.getString("area_name"))
                .put("interval_time", range)
                .put("interval_id", slot.get("interval_id").toString())
                .put("area_id", slot.get("column_id").toString())
                .put("price", "0");
        require(
            detail.getString("area_id").matches("[1-9][0-9]{0,8}")
                && detail.getString("interval_id").matches("[1-9][0-9]{0,8}"),
            "SLOT_SHAPE");
        eligible.add(detail);
      }
    }
    require(!eligible.isEmpty(), "NO_AVAILABLE_TARGET");
    eligible.sort(Comparator.comparingInt(o -> o.optInt("area_id")));
    return eligible.get(0);
  }

  public byte[] prepare(JSONObject target, JSONObject detail) throws Exception {
    require(!prepared, "PREPARE_ONCE");
    prepared = true;
    JSONObject info =
        read("/index/Stadium/getVenueConfig", targetFields(target))
            .getJSONObject("data")
            .getJSONObject("info");
    require(!flag(info, "needRemark") && !flag(info, "verify_phone"), "EXTRA_INPUT_REQUIRED");
    Map<String, String> values = targetFields(target);
    values.put("selected", new JSONArray().put(detail).toString());
    values.put("is_academy", "1");
    values.put("ids", "");
    Map<String, String> ref = targetFields(target);
    ref.put("name", target.getString("stadium_name"));
    ref.put("project_name", target.getString("project_name"));
    referer = BASE + "/bdlp_h5_fitness_test/view/stadium/choose.html?" + encode(ref);
    call("/index/stadium/chooseVerify", values, false);
    info =
        read("/index/Stadium/getVenueConfig", targetFields(target))
            .getJSONObject("data")
            .getJSONObject("info");
    require(
        flag(info, "isCanAppoint") && !flag(info, "needRemark") && !flag(info, "verify_phone"),
        "EXTRA_INPUT_REQUIRED");
    ref.put("list", new JSONArray().put(detail).toString());
    ref.put("is_academy", "1");
    ref.put("academy_name", "");
    ref.put("mark", "");
    ref.put("ids", "");
    ref.put("p_count", "0");
    ref.put("price", "0");
    referer = BASE + "/bdlp_h5_fitness_test/view/stadium/confirm.html?" + encode(ref);
    TreeMap<String, String> query = new TreeMap<>();
    query.put("r", Double.toString(new java.security.SecureRandom().nextDouble()));
    query.put("t", Long.toString(System.currentTimeMillis() / 1000));
    String canonical =
        "r" + query.get("r") + "t" + query.get("t") + "uid" + fields.get("uid") + "Lptiyu_123!%$";
    query.put(
        "sign",
        hex(MessageDigest.getInstance("MD5").digest(canonical.getBytes(StandardCharsets.UTF_8))));
    return call("/index/index/captcha", query, true).image;
  }

  public void submit(JSONObject target, JSONObject detail, String prediction, Runnable guard)
      throws Exception {
    require(prepared && !submitted && prediction.matches("[A-Za-z0-9]{4}"), "SUBMIT_REFUSED");
    submitted = true;
    Map<String, String> body = targetFields(target);
    body.put("stadium_name", target.getString("stadium_name"));
    body.put("project_name", target.getString("project_name"));
    body.put("captcha", prediction.toLowerCase(Locale.ROOT));
    for (String k : new String[] {"academy_name", "mark", "uids"}) body.put(k, "");
    body.put("is_academy", "1");
    body.put("is_vip", "0");
    body.put("pay_type", "1");
    for (Map.Entry<String, String> e : map(detail).entrySet())
      body.put("details[0][" + e.getKey() + "]", e.getValue());
    guard.run();
    call("/index/Stadium/addOrder", body, false);
  }

  public JSONArray matches(JSONArray orders, JSONObject target, String range, String area)
      throws Exception {
    JSONArray found = new JSONArray();
    for (int i = 0; i < orders.length(); i++) {
      JSONObject row = orders.getJSONObject(i);
      boolean same = fields.get("uid").equals(row.optString("uid"));
      for (String k :
          new String[] {"stadium_id", "venue_id", "category_id", "stadium_name", "project_name"})
        same &= target.getString(k).equals(row.optString(k));
      if (!same || TERMINAL.contains(row.optString("audit_status_text"))) continue;
      JSONArray details = row.getJSONArray("detail");
      boolean matched = false;
      for (int j = 0; j < details.length(); j++) {
        JSONObject d = details.getJSONObject(j);
        String status = d.optString("status_text", row.optString("audit_status_text"));
        if (target.getString("date").equals(d.optString("date"))
            && range.equals(normalizedRange(d.optString("interval_time")))
            && Set.of("已预约", "未核销", "预约成功", "待核销").contains(status)
            && (area == null || area.equals(d.optString("area_id")))) {
          require(
              d.optString("area_id").matches("[1-9][0-9]{0,8}")
                  && !d.optString("area_name").isEmpty(),
              "ORDER_SHAPE");
          matched = true;
        }
      }
      if (matched) found.put(row);
    }
    return found;
  }

  public static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }

  private static boolean flag(JSONObject info, String key) {
    return info.optBoolean(key, false) || info.optInt(key, 0) == 1;
  }

  public static String normalizedRange(String value) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "\\s*(\\d{1,2}):([0-5]\\d)(?::00)?\\s*[-—–]\\s*(\\d{1,2}):([0-5]\\d)(?::00)?\\s*")
            .matcher(value);
    if (!m.matches() || Integer.parseInt(m.group(1)) > 23 || Integer.parseInt(m.group(3)) > 23)
      return "";
    return String.format(
        Locale.ROOT,
        "%02d:%s-%02d:%s",
        Integer.parseInt(m.group(1)),
        m.group(2),
        Integer.parseInt(m.group(3)),
        m.group(4));
  }

  @Override
  public void close() {
    cookie = "";
    fields.clear();
  }

  public static final class HttpWire implements Wire {
    @Override
    public Reply call(
        String route, Map<String, String> values, String cookie, String referer, boolean image)
        throws Exception {
      require(ROUTES.contains(route), "ROUTE_REFUSED");
      String body = encode(values);
      HttpURLConnection c =
          (HttpURLConnection)
              new URL(BASE + PREFIX + route + (image ? "?" + body : "")).openConnection();
      c.setConnectTimeout(8000);
      c.setReadTimeout(8000);
      c.setInstanceFollowRedirects(false);
      c.setRequestMethod(image ? "GET" : "POST");
      c.setRequestProperty("Referer", referer);
      c.setRequestProperty("Origin", BASE);
      c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
      c.setRequestProperty("User-Agent", "Mozilla/5.0 XmuBooker/0.2");
      if (!cookie.isEmpty()) c.setRequestProperty("Cookie", "PHPSESSID=" + cookie);
      try {
        if (!image) {
          c.setDoOutput(true);
          c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
          byte[] data = body.getBytes(StandardCharsets.UTF_8);
          c.setFixedLengthStreamingMode(data.length);
          try (OutputStream out = c.getOutputStream()) {
            out.write(data);
          }
        }
        require(c.getResponseCode() == 200, "SCHOOL_HTTP_FAILED");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) != -1) {
            require(bytes.size() + n <= 2000000, "RESPONSE_TOO_LARGE");
            bytes.write(buf, 0, n);
          }
        }
        Reply reply = new Reply(image ? null : new JSONObject(bytes.toString("UTF-8")));
        if (image) {
          require(
              c.getContentType() != null && c.getContentType().startsWith("image/"),
              "CAPTCHA_IMAGE_INVALID");
          reply.image = bytes.toByteArray();
        }
        for (Map.Entry<String, List<String>> entry : c.getHeaderFields().entrySet())
          if ("Set-Cookie".equalsIgnoreCase(entry.getKey()))
            for (String v : entry.getValue()) {
              String first = v.split(";", 2)[0];
              if (first.startsWith("PHPSESSID=")) {
                String session = first.substring(10);
                require(session.matches("[A-Za-z0-9,-]{1,128}"), "INVALID_SESSION");
                reply.session = session;
              }
            }
        return reply;
      } finally {
        c.disconnect();
      }
    }
  }
}
