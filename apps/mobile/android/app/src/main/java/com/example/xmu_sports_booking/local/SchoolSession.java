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
      Set.of("已取消", "已退款", "已拒绝", "预约失败", "审核不通过", "已核销", "已完成", "已结束");
  private final TreeMap<String, String> fields;
  private final Wire wire;
  private boolean submitted = false, prepared = false;
  private String cookie = "", referer;
  private boolean readOnly;
  private JSONArray firstOrders;
  private final Map<String, JSONObject> stadiumDetailsCache = new HashMap<>();
  private static final ReadSessionCache<SportsProtocol.VerifiedSession> readSessions = new ReadSessionCache<>(60_000);

  private static synchronized void invalidateReadSession() {
    readSessions.clear();
  }

  public interface Wire {
    Reply call(
        String route, Map<String, String> values, String cookie, String referer, boolean image)
        throws Exception;

    /** Fixed public presentation cookies of the official page; transports that ignore them may skip. */
    default void useCompanionCookies(Map<String, String> cookies) {}
  }

  public static final class Reply {
    public JSONObject json;
    public byte[] image;
    public String session;

    public Reply(JSONObject json) {
      this.json = json;
    }
  }

  /** Contains only the route, status and a redacted school explanation, never a response dump. */
  public static final class RejectedException extends Exception {
    public final String stage, schoolMessage;
    public final int status;

    RejectedException(String stage, int status, String schoolMessage) {
      super("SCHOOL_REJECTED");
      this.stage = stage;
      this.status = status;
      this.schoolMessage = schoolMessage;
    }

    public String description() {
      return "学校拒绝请求（步骤：" + stage + "，状态：" + status + "）：" + schoolMessage;
    }
  }

  private String rejectionMessage(JSONObject response) {
    String message = "";
    if (response != null) {
      for (String key : new String[] {"msg", "message", "info", "error"}) {
        Object value = response.opt(key);
        if (value instanceof String && !((String) value).isBlank()) {
          message = (String) value;
          break;
        }
      }
    }
    message = message.replaceAll("<[^>]*>", " ")
        .replaceAll("[\\p{Cntrl}]", " ")
        .replaceAll("https?://\\S+", "[链接已隐藏]");
    for (String value : fields.values())
      if (value.length() >= 3) message = message.replace(value, "[已隐藏]");
    if (!cookie.isEmpty()) message = message.replace(cookie, "[已隐藏]");
    message = message.replaceAll(
        "(?i)(token|sign|cookie|phpsessid|student_num|card_id|uid)\\s*[:=]\\s*[^\\s,;，；]+", "$1=[已隐藏]")
        .replaceAll("[0-9]{5,}", "[号码已隐藏]").trim();
    if (message.isEmpty()) return "学校未提供详细原因";
    return message.substring(0, Math.min(message.length(), 160));
  }

  private static String routeStage(String route) {
    switch (route.substring(route.lastIndexOf('/') + 1)) {
      case "checklogin": return "学校登录";
      case "mySubscribe": return "读取学校订单";
      case "orderDetails": return "读取订单详情";
      case "getStadiumDetails": return "读取场馆详情";
      case "getStadiumList": return "读取场馆目录";
      case "getInterval": return "查询时段";
      case "getVenueConfig": return "读取预约规则";
      case "chooseVerify": return "选择时段校验";
      case "addOrder": return "提交预约";
      default: return "学校请求";
    }
  }

  public static SchoolSession open(Context context) throws Exception {
    TokenVault vault = new TokenVault(context);
    String saved = vault.loadSecret("sealed");
    require(saved != null, "AUTH_REQUIRED");
    JSONObject record = new JSONObject(saved), f = record.getJSONObject("fields");
    TreeMap<String, String> values = map(f);
    JSONObject fresh = SportsProtocol.verifyAndRecord(encode(values), values.get("student_num"), stage -> {});
    require(vault.replaceRecordIfCurrent(saved, fresh), "AUTHORIZATION_CHANGED");
    return new SchoolSession(fresh, new HttpWire());
  }

  /** Read operations share a verified session for at most 60 seconds; booking always uses open(). */
  public static synchronized SchoolSession openReadOnly(Context context) throws Exception {
    TokenVault vault = new TokenVault(context);
    String saved = vault.loadSecret("sealed");
    if (saved == null) readSessions.clear();
    require(saved != null, "AUTH_REQUIRED");
    long now = android.os.SystemClock.elapsedRealtime();
    SportsProtocol.VerifiedSession readSession = readSessions.get(saved, now);
    boolean fresh = readSession == null;
    if (fresh) {
      JSONObject fields = new JSONObject(saved).getJSONObject("fields");
      SportsProtocol.VerifiedSession verified = SportsProtocol.verifySession(
          encode(map(fields)), fields.getString("student_num"), stage -> {});
      require(vault.replaceRecordIfCurrent(saved, verified.record), "AUTHORIZATION_CHANGED");
      String updated = vault.loadSecret("sealed");
      require(updated != null && new JSONObject(updated).optLong("authorization_version", 0) ==
          new JSONObject(saved).optLong("authorization_version", 0), "AUTHORIZATION_CHANGED");
      readSession = verified;
      readSessions.put(updated, verified, android.os.SystemClock.elapsedRealtime());
    }
    SchoolSession result = new SchoolSession(readSession.record, new HttpWire(), readSession.session,
        fresh ? readSession.firstOrders : null, true);
    result.readOnly = true;
    return result;
  }

  /** Injectable transport is used only by synthetic native tests. */
  public SchoolSession(JSONObject record, Wire wire) throws Exception {
    this(record, wire, null);
  }

  /** Attach an owner-verified original sports session without creating another login session. */
  public SchoolSession(JSONObject record, Wire wire, String existingSession) throws Exception {
    this(record, wire, existingSession, null, false);
  }

  private SchoolSession(JSONObject record, Wire wire, String existingSession,
      JSONArray initialOrders, boolean verified) throws Exception {
    fields =
        SportsProtocol.parse(
            encode(map(record.getJSONObject("fields"))),
            record.getJSONObject("fields").getString("student_num"));
    this.wire = wire;
    // The official page sends login_type and COOKIE_IS_APP_UI with every request, including
    // the first checkLogin; a successful browser addOrder carried both next to PHPSESSID.
    String loginType = fields.get("login_type");
    if (loginType != null && loginType.matches("[0-9]{1,2}")) {
      LinkedHashMap<String, String> companion = new LinkedHashMap<>();
      companion.put("login_type", loginType);
      companion.put("COOKIE_IS_APP_UI", "1".equals(loginType) ? "1" : "0");
      wire.useCompanionCookies(companion);
    }
    referer = BASE + "/bdlp_h5_fitness_test/view/stadium/home.html?" + encode(fields);
    if (existingSession != null) {
      require(existingSession.matches("[A-Za-z0-9,-]{1,128}"), "INVALID_SESSION");
      cookie = existingSession;
      if (verified && initialOrders == null) return;
      JSONArray rows = verified ? new JSONArray(initialOrders.toString()) :
          read("/index/stadium/mySubscribe", Map.of("p", "1")).getJSONArray("data");
      for (int i = 0; i < rows.length(); i++)
        require(fields.get("uid").equals(rows.getJSONObject(i).optString("uid")), "ORDERS_OWNER_MISMATCH");
      if (verified) firstOrders = rows;
      return;
    }
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
    require(!readOnly || (!image && !route.endsWith("addOrder") && !route.endsWith("chooseVerify")
        && !route.endsWith("checklogin") && !route.endsWith("captcha")), "READ_ROUTE_REFUSED");
    Reply r = wire.call(route, values, cookie, referer, image);
    if (r.session != null) {
      require(cookie.isEmpty() || cookie.equals(r.session), "SESSION_CHANGED");
      cookie = r.session;
    }
    if (!image && (r.json == null || r.json.optInt("status", -1) != 1)) {
      if (readOnly) invalidateReadSession();
      throw new RejectedException(routeStage(route),
          r.json == null ? -1 : r.json.optInt("status", -1), rejectionMessage(r.json));
    }
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
    return orders(expand, null);
  }

  private JSONArray orders(boolean expand, String stopAtOrder) throws Exception {
    JSONArray result = new JSONArray();
    Set<String> seen = new HashSet<>();
    boolean restarted = false;
    for (int read = 0, page = 1; read < 100; read++, page++) {
      JSONArray rows;
      if (page == 1 && firstOrders != null) { rows = firstOrders; firstOrders = null; }
      else rows = read("/index/stadium/mySubscribe", Map.of("p", Integer.toString(page))).getJSONArray("data");
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
        if (id.equals(stopAtOrder)) return result;
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
    return inventory(target, false);
  }

  /** Old display catalogs guessed child IDs; repair only a query, never silently retarget execution. */
  public JSONObject inventoryForQuery(JSONObject target) throws Exception {
    return inventory(target, true);
  }

  static JSONObject resolveVenue(JSONObject target, JSONObject details, boolean repairLegacy) throws Exception {
    JSONArray venues = details.optJSONArray("venue");
    LinkedHashMap<String, JSONObject> candidates = new LinkedHashMap<>();
    if (venues != null) for (int i = 0; i < venues.length(); i++) {
      JSONObject project = venues.getJSONObject(i);
      if (!project.optString("category_id").equals(target.getString("category_id"))) continue;
      JSONArray list = project.optJSONArray("list");
      if (list == null) continue;
      for (int j = 0; j < list.length(); j++) {
        String id = list.getJSONObject(j).optString("venue_id");
        if (!id.matches("[1-9][0-9]{0,8}")) continue;
        JSONObject resolved = new JSONObject(target.toString());
        resolved.put("venue_id", id).put("project_name", project.getString("name"))
            .put("stadium_name", details.getString("name"));
        candidates.put(id, resolved);
      }
    }
    JSONObject exact = candidates.get(target.getString("venue_id"));
    if (exact != null) return exact;
    if (repairLegacy && candidates.size() == 1) return candidates.values().iterator().next();
    require(candidates.isEmpty(), "VENUE_MAPPING_CHANGED");
    return null;
  }

  private JSONObject inventory(JSONObject target, boolean repairLegacy) throws Exception {
    JSONObject details = stadiumDetails(target.getString("stadium_id"));
    String detailsName = details.optString("name", "").trim();
    String targetName = target.optString("stadium_name", "").trim();
    require(
        !detailsName.isEmpty() && (detailsName.equals(targetName)
            || detailsName.contains(targetName)
            || targetName.contains(detailsName)),
        "VENUE_MISMATCH");
    JSONObject resolved = resolveVenue(target, details, repairLegacy);
    if (resolved == null) {
      JSONObject emptyInv = new JSONObject();
      emptyInv.put("is_can_choose", 0);
      emptyInv.put("is_can_choose_tips", "该场馆当前暂未开放预约或暂无时段");
      emptyInv.put("time_slot_list", new JSONArray());
      return emptyInv;
    }
    Map<String, String> values = targetFields(resolved);
    Object userRange = details.opt("user_range");
    values.put("user_range", userRange != null ? userRange.toString() : "[7]");
    return read("/stadium/interval/getInterval", values).getJSONObject("data")
        .put("resolved_target", resolved);
  }

  public JSONObject stadiumList() throws Exception {
    return read("/index/Stadium/getStadiumList", fields).getJSONObject("data");
  }

  public JSONObject stadiumDetails(String id) throws Exception {
    JSONObject cached = stadiumDetailsCache.get(id);
    if (cached == null) {
      cached = read("/index/Stadium/getStadiumDetails", Map.of("id", id)).getJSONObject("data");
      stadiumDetailsCache.put(id, new JSONObject(cached.toString()));
    }
    return new JSONObject(cached.toString());
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
            || slot.getDouble("selected") >= capacity) continue;
        // A school-listed price is not an availability flag. Keep it in the order details.
        java.math.BigDecimal price;
        try {
          price = new java.math.BigDecimal(slot.get("price").toString());
        } catch (NumberFormatException e) {
          throw new IllegalStateException("SLOT_SHAPE");
        }
        require(price.signum() >= 0, "SLOT_SHAPE");
        JSONObject detail =
            new JSONObject()
                .put("date", target.getString("date"))
                .put("week", row.get("week").toString())
                .put("week_msg", row.getString("week_name"))
                .put("area_name", slot.getString("area_name"))
                .put("interval_time", range)
                .put("interval_id", slot.get("interval_id").toString())
                .put("area_id", slot.get("column_id").toString())
                .put("price", price.toPlainString());
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
    // Match choose.html + jQuery.param: an empty companion array emits no ids field.
    // Sending ids="" makes the PHP controller treat a string as a companion array.
    values.remove("category_id");
    values.put("selected", new JSONArray().put(detail).toString());
    values.put("is_academy", "1");
    Map<String, String> ref = targetFields(target);
    ref.put("name", target.getString("stadium_name"));
    ref.put("project_name", target.getString("project_name"));
    referer = BASE + "/bdlp_h5_fitness_test/view/stadium/choose.html?" + encode(ref);
    call("/index/stadium/chooseVerify", values, false);
    ref.put("list", new JSONArray().put(detail).toString());
    ref.put("is_academy", "1");
    ref.put("academy_name", "");
    ref.put("mark", "");
    // Official personal-booking confirmation carries a JSON companion array, even when empty.
    ref.put("ids", URLEncoder.encode("[]", "UTF-8"));
    ref.put("p_count", "0");
    // The official confirm page itself writes price=NaN into this Referer (R54 HAR).
    ref.put("price", "NaN");
    referer = BASE + "/bdlp_h5_fitness_test/view/stadium/confirm.html?" + encode(ref);
    // The official confirmation page reads its rules with the confirmation Referer already set.
    info = read("/index/Stadium/getVenueConfig", targetFields(target))
        .getJSONObject("data").getJSONObject("info");
    require(flag(info, "isCanAppoint") && !flag(info, "needRemark") && !flag(info, "verify_phone"),
        "EXTRA_INPUT_REQUIRED");
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
    for (String k : new String[] {"academy_name", "mark"}) body.put(k, "");
    // jQuery.param omits the empty uids array. A scalar uids="" is a different PHP type.
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
    return matches(orders, target, range, area, null);
  }

  private JSONArray matches(JSONArray orders, JSONObject target, String range, String area, String areaName)
      throws Exception {
    JSONArray found = new JSONArray();
    for (int i = 0; i < orders.length(); i++) {
      JSONObject row = orders.getJSONObject(i);
      boolean same = fields.get("uid").equals(row.optString("uid"));
      boolean missingIds = false;
      for (String k :
          new String[] {"stadium_id", "venue_id", "category_id"}) {
        String id = row.optString(k);
        if (id.isEmpty()) missingIds = true;
        else same &= target.getString(k).equals(id);
      }
      // The school's own-order list omits all three IDs. Require both official names then;
      // an explicitly supplied conflicting ID must never be bypassed by a name match.
      if (missingIds) same &= target.getString("stadium_name").equals(row.optString("stadium_name"))
          && target.getString("project_name").equals(row.optString("project_name"));
      if (!same || TERMINAL.contains(row.optString("audit_status_text"))) continue;
      JSONArray details = row.getJSONArray("detail");
      boolean matched = false;
      for (int j = 0; j < details.length(); j++) {
        JSONObject d = details.getJSONObject(j);
        String status = d.has("status_text") ? d.optString("status_text")
            : d.has("status") ? state(d.optInt("status", -1)) : row.optString("audit_status_text");
        if (target.getString("date").equals(d.optString("date"))
            && range.equals(normalizedRange(d.optString("interval_time")))
            && Set.of("已预约", "未核销", "预约成功", "待核销", "待支付").contains(status)
            && (area == null || (!d.optString("area_id").isEmpty()
                ? area.equals(d.optString("area_id"))
                : areaName != null && !areaName.isEmpty() && areaName.equals(d.optString("area_name"))))) {
          require(
              (d.optString("area_id").isEmpty() || d.optString("area_id").matches("[1-9][0-9]{0,8}"))
                  && !d.optString("area_name").isEmpty(),
              "ORDER_SHAPE");
          matched = true;
        }
      }
      if (matched) found.put(row);
    }
    return found;
  }

  public JSONArray matchesTask(JSONArray orders, JSONObject target, JSONObject receipt)
      throws Exception {
    JSONArray ranges = receipt.has("range")
        ? new JSONArray().put(receipt.getString("range")) : target.getJSONArray("ranges");
    JSONObject detail = receipt.optJSONObject("detail");
    String area = detail == null ? null : detail.optString("area_id", null);
    String areaName = detail == null ? null : detail.optString("area_name", null);
    LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
    for (int i = 0; i < ranges.length(); i++) {
      JSONArray matches = matches(orders, target, ranges.getString(i), area, areaName);
      for (int j = 0; j < matches.length(); j++) {
        JSONObject row = matches.getJSONObject(j);
        found.put(row.getString("order_id"), row);
      }
    }
    return new JSONArray(found.values());
  }

  public static boolean needsPayment(JSONObject order) {
    if ("待支付".equals(order.optString("audit_status_text"))) return true;
    JSONArray details = order.optJSONArray("detail");
    if (details != null) for (int i = 0; i < details.length(); i++) {
      JSONObject row = details.optJSONObject(i);
      if (row != null && ("待支付".equals(row.optString("status_text")) || row.optInt("status", -1) == 6))
        return true;
    }
    return false;
  }

  public JSONObject entryPass(JSONObject target, JSONObject receipt) throws Exception {
    JSONObject order = receipt.optJSONObject("order");
    require(order != null && fields.get("uid").equals(order.optString("uid")), "OWNER_MISMATCH");
    String id = order.getString("order_id");
    JSONObject detail = read("/index/stadium/orderDetails", Map.of("order_id", id, "order_num", ""))
        .getJSONObject("data");
    require(id.equals(detail.optString("order_id")), "ORDER_DETAILS_IDENTITY_MISMATCH");
    require(!detail.has("uid") || fields.get("uid").equals(detail.optString("uid")), "OWNER_MISMATCH");
    return entryPasses(detail, target, receipt);
  }

  /** Resolve only an order found in a fresh, owner-verified school order list. */
  public JSONObject orderEntryPass(String id) throws Exception {
    require(id != null && !id.isBlank() && id.length() <= 128, "ENTRY_PASS_UNAVAILABLE");
    JSONArray orders = orders(false, id);
    JSONObject owned = null;
    for (int i = 0; i < orders.length(); i++)
      if (id.equals(orders.getJSONObject(i).optString("order_id"))) owned = orders.getJSONObject(i);
    require(owned != null && fields.get("uid").equals(owned.optString("uid")), "OWNER_MISMATCH");
    JSONObject detail = read("/index/stadium/orderDetails", Map.of("order_id", id, "order_num", ""))
        .getJSONObject("data");
    require(id.equals(detail.optString("order_id")), "ORDER_DETAILS_IDENTITY_MISMATCH");
    require(!detail.has("uid") || fields.get("uid").equals(detail.optString("uid")), "OWNER_MISMATCH");
    JSONArray rows = detail.optJSONArray("details"), passes = new JSONArray();
    if (rows != null) for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      if (row.optInt("status", -1) != 1) continue;
      JSONObject target = new JSONObject().put("stadium_name", owned.getString("stadium_name"))
          .put("project_name", owned.getString("project_name")).put("date", row.getString("date"))
          .put("ranges", new JSONArray().put(row.getString("interval_time")));
      // Restrict to this individual entry so multi-day orders never duplicate passes.
      JSONObject single = new JSONObject(detail.toString()).put("details", new JSONArray().put(row));
      JSONArray found = entryPasses(single, target, new JSONObject()).getJSONArray("passes");
      passes.put(found.getJSONObject(0));
    }
    require(passes.length() > 0, "ENTRY_PASS_UNAVAILABLE");
    return new JSONObject().put("passes", passes);
  }

  static JSONObject entryPasses(JSONObject order, JSONObject target, JSONObject receipt) throws Exception {
    for (String key : new String[] {"stadium_name", "project_name"})
      require(target.getString(key).equals(order.optString(key)), "ENTRY_PASS_UNAVAILABLE");
    require(order.opt("qrcode") instanceof String, "ENTRY_PASS_UNAVAILABLE");
    String code = order.getString("qrcode");
    require(!code.isEmpty() && code.length() <= 512 && !code.matches("(?s).*\\p{Cntrl}.*"), "ENTRY_PASS_UNAVAILABLE");
    JSONArray ranges = receipt.has("range") ? new JSONArray().put(receipt.getString("range"))
        : target.getJSONArray("ranges");
    Set<String> allowed = new HashSet<>();
    for (int i = 0; i < ranges.length(); i++) allowed.add(normalizedRange(ranges.getString(i)));
    JSONObject selected = receipt.optJSONObject("detail");
    JSONArray rows = order.optJSONArray("details"), passes = new JSONArray();
    if (rows != null) for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.getJSONObject(i);
      String range = normalizedRange(row.optString("interval_time"));
      if (row.optInt("status", -1) != 1 || !target.getString("date").equals(row.optString("date"))
          || range.isEmpty() || !allowed.contains(range)) continue;
      if (selected != null) {
        String areaId = row.optString("area_id");
        if (!areaId.isEmpty()) {
          if (!areaId.equals(selected.optString("area_id"))) continue;
        } else if (!row.optString("area_name").equals(selected.optString("area_name"))) continue;
      }
      String detailsId = row.optString("details_id");
      require(detailsId.matches("[1-9][0-9]{0,31}"), "ENTRY_PASS_UNAVAILABLE");
      // Exact official order.html contract: details_id + '|' + server-issued qrcode.
      passes.put(new JSONObject().put("date", row.getString("date")).put("range", range)
          .put("area_name", row.optString("area_name")).put("payload", detailsId + "|" + code));
    }
    require(passes.length() > 0, "ENTRY_PASS_UNAVAILABLE");
    return new JSONObject().put("passes", passes);
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
    private final String userAgent;
    private Map<String, String> companion = Map.of();

    @Override
    public void useCompanionCookies(Map<String, String> cookies) {
      LinkedHashMap<String, String> safe = new LinkedHashMap<>();
      for (String name : new String[] {"login_type", "COOKIE_IS_APP_UI"}) {
        String value = cookies.get(name);
        if (value != null && value.matches("[0-9]{1,2}")) safe.put(name, value);
      }
      companion = safe;
    }

    Map<String, String> companionForTest() {
      return companion;
    }

    static String cookieHeader(String session, Map<String, String> companion) {
      List<String> parts = new ArrayList<>();
      for (Map.Entry<String, String> e : companion.entrySet())
        parts.add(e.getKey() + "=" + e.getValue());
      if (!session.isEmpty()) parts.add("PHPSESSID=" + session);
      return String.join("; ", parts);
    }

    /** User agent of the official WeChat mini-program page, as captured in the R54 HAR. */
    static final String OFFICIAL_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/144.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI "
            + "MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) "
            + "UnifiedPCWindowsWechat(0xf254200d) XWEB/26073 miniProgram/wx81a2b2fa90759cb7";

    public HttpWire() { this(OFFICIAL_USER_AGENT); }

    public HttpWire(String userAgent) {
      if (userAgent == null || userAgent.length() > 512 || userAgent.contains("\r") || userAgent.contains("\n"))
        throw new IllegalArgumentException("INVALID_USER_AGENT");
      this.userAgent = userAgent;
    }
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
      // Mirror the headers the official mini-program page sends (R54 HAR, 2026-11-12).
      c.setRequestProperty("Referer", referer);
      if (image) {
        c.setRequestProperty(
            "Accept", "image/wxpic,image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        c.setRequestProperty("Sec-Fetch-Mode", "no-cors");
        c.setRequestProperty("Sec-Fetch-Dest", "image");
      } else {
        c.setRequestProperty("Origin", BASE);
        c.setRequestProperty("X-Requested-With", "XMLHttpRequest");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Sec-Fetch-Mode", "cors");
        c.setRequestProperty("Sec-Fetch-Dest", "empty");
      }
      c.setRequestProperty("Sec-Fetch-Site", "same-origin");
      c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
      c.setRequestProperty("User-Agent", userAgent);
      String cookieHeader = cookieHeader(cookie, companion);
      if (!cookieHeader.isEmpty()) c.setRequestProperty("Cookie", cookieHeader);
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
