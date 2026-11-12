package com.example.xmu_sports_booking.local;

import static org.junit.Assert.*;

import com.example.xmu_sports_booking.capture.SportsProtocol;
import java.util.*;
import org.json.*;
import org.junit.Test;

public class SchoolSessionTest {
  private JSONObject record() throws Exception {
    TreeMap<String, String> f =
        new TreeMap<>(
            Map.of(
                "timestamp",
                "1793588880",
                "nonce",
                "123456",
                "course_id",
                "0",
                "uid",
                "4242",
                "card_id",
                "12345678",
                "login_type",
                "4",
                "type",
                "1",
                "school_id",
                "1",
                "student_num",
                "12345678",
                "user_type",
                "2"));
    f.put("token", "synthetic-test-token");
    f.put("sign", SportsProtocol.sign(f));
    return new JSONObject().put("fields", new JSONObject(f));
  }

  private JSONObject target() throws Exception {
    return new JSONObject(
        "{stadium_id:'16',venue_id:'14',category_id:'8',stadium_name:'Synthetic"
            + " Gym',project_name:'Fitness',date:'2026-11-20'}");
  }

  private JSONObject slot() throws Exception {
    return new JSONObject(
        "{is_can_choose:1,personal_num:10,venue_appointment_min_num:0,time_slot_list:[{date:'2026-11-20',week:'5',week_name:'周五',time_range:'12:00-13:30',slots:[{date:'2026-11-20',status:'available',selected:2,price:0,area_name:'A',column_id:'67',interval_id:'81'}]}]}");
  }

  private JSONObject order(String uid) throws Exception {
    return new JSONObject(
        "{order_id:'synthetic-order',uid:'"
            + uid
            + "',stadium_id:'16',venue_id:'14',category_id:'8',stadium_name:'Synthetic"
            + " Gym',project_name:'Fitness',detail:[{date:'2026-11-20',interval_time:'12:00-13:30',area_name:'A',area_id:'67',status_text:'已预约'}]}");
  }

  class Wire implements SchoolSession.Wire {
    int submits = 0, pages = 0;
    boolean overlap = false;
    JSONArray rows = new JSONArray();
    Map<String, String> sent;

    public SchoolSession.Reply call(
        String route, Map<String, String> values, String cookie, String referer, boolean image)
        throws Exception {
      JSONObject data = new JSONObject();
      SchoolSession.Reply reply =
          new SchoolSession.Reply(new JSONObject().put("status", 1).put("data", data));
      if (route.endsWith("checklogin")) {
        reply.session = "synthetic-session";
        return reply;
      }
      assertEquals("synthetic-session", cookie);
      if (route.endsWith("mySubscribe")) {
        pages++;
        reply.json.put("data", rows);
        return reply;
      }
      if (route.endsWith("getStadiumDetails"))
        data.put("name", "Synthetic Gym")
            .put("user_range", 0)
            .put("venue", new JSONArray("[{category_id:8,name:'Fitness',list:[{venue_id:14}]}]"));
      if (route.endsWith("getInterval")) reply.json.put("data", slot());
      if (route.endsWith("getVenueConfig"))
        data.put("info", new JSONObject("{isCanAppoint:true,needRemark:0,verify_phone:0}"));
      if (route.endsWith("captcha")) reply.image = new byte[32];
      if (route.endsWith("addOrder")) {
        submits++;
        sent = new TreeMap<>(values);
      }
      return reply;
    }
  }

  @Test
  public void inventoryAndSubmissionUseScopedCookieAndLowercase() throws Exception {
    Wire w = new Wire();
    try (SchoolSession s = new SchoolSession(record(), w)) {
      JSONObject detail = SchoolSession.select(s.inventory(target()), target(), "12:00-13:30");
      s.prepare(target(), detail);
      final int[] guards = {0};
      s.submit(target(), detail, "AB12", () -> guards[0]++);
      assertEquals(1, w.submits);
      assertEquals(1, guards[0]);
      assertEquals("ab12", w.sent.get("captcha"));
      assertEquals("67", w.sent.get("details[0][area_id]"));
      assertThrows(Exception.class, () -> s.submit(target(), detail, "AB12", () -> {}));
      assertEquals(1, w.submits);
    }
  }

  @Test
  public void deniedDurableGuardNeverSends() throws Exception {
    Wire w = new Wire();
    try (SchoolSession s = new SchoolSession(record(), w)) {
      JSONObject d = SchoolSession.select(slot(), target(), "12:00-13:30");
      s.prepare(target(), d);
      assertThrows(
          Exception.class,
          () ->
              s.submit(
                  target(),
                  d,
                  "AB12",
                  () -> {
                    throw new IllegalStateException("REFUSED");
                  }));
      assertEquals(0, w.submits);
    }
  }

  @Test
  public void foreignOrdersAreRejected() throws Exception {
    Wire w = new Wire();
    w.rows.put(order("other"));
    try (SchoolSession s = new SchoolSession(record(), w)) {
      assertThrows(Exception.class, () -> s.orders(false));
    }
  }

  @Test
  public void overlappingPaginationFailsClosed() throws Exception {
    Wire w = new Wire();
    for (int i = 0; i < 10; i++) w.rows.put(order("4242").put("order_id", "order-" + i));
    try (SchoolSession s = new SchoolSession(record(), w)) {
      assertThrows(Exception.class, () -> s.orders(false));
      assertEquals(4, w.pages);
    }
  }

  @Test
  public void completeTenItemPagesReadTheNextPage() throws Exception {
    Wire w =
        new Wire() {
          @Override
          public SchoolSession.Reply call(
              String r, Map<String, String> v, String c, String f, boolean i) throws Exception {
            SchoolSession.Reply reply = super.call(r, v, c, f, i);
            if (r.endsWith("mySubscribe") && v.get("p").equals("2"))
              reply.json.put("data", new JSONArray());
            return reply;
          }
        };
    for (int i = 0; i < 10; i++) w.rows.put(order("4242").put("order_id", "order-" + i));
    try (SchoolSession s = new SchoolSession(record(), w)) {
      assertEquals(10, s.orders(false).length());
      assertEquals(2, w.pages);
    }
  }

  @Test
  public void wrongDatesTimesAreasOrCancelledOrdersDoNotConfirm() throws Exception {
    Wire w = new Wire();
    try (SchoolSession s = new SchoolSession(record(), w)) {
      JSONArray rows = new JSONArray().put(order("4242"));
      assertEquals(1, s.matches(rows, target(), "12:00-13:30", "67").length());
      assertEquals(0, s.matches(rows, target(), "13:30-14:30", "67").length());
      assertEquals(0, s.matches(rows, target(), "12:00-13:30", "68").length());
      assertEquals(
          0, s.matches(rows, target().put("date", "2026-11-21"), "12:00-13:30", "67").length());
      rows.getJSONObject(0).put("audit_status_text", "已取消");
      assertEquals(0, s.matches(rows, target(), "12:00-13:30", "67").length());
    }
  }

  @Test
  public void paidFullAndLockedSlotsCannotBeSelected() throws Exception {
    for (String mode : new String[] {"paid", "full", "locked"}) {
      JSONObject data = slot(),
          s =
              data.getJSONArray("time_slot_list")
                  .getJSONObject(0)
                  .getJSONArray("slots")
                  .getJSONObject(0);
      if (mode.equals("paid")) s.put("price", 1);
      if (mode.equals("full")) s.put("selected", 10);
      if (mode.equals("locked")) s.put("status", "locked");
      assertThrows(Exception.class, () -> SchoolSession.select(data, target(), "12:00-13:30"));
    }
  }

  @Test
  public void noArbitraryWriteRoutesViaReadInterface() throws Exception {
    try (SchoolSession s = new SchoolSession(record(), new Wire())) {
      assertThrows(Exception.class, () -> s.read("/index/Stadium/addOrder", Map.of()));
      assertThrows(Exception.class, () -> s.read("/arbitrary", Map.of()));
    }
  }

  @Test
  public void terminalOrdersNeedNoDetailRequest() throws Exception {
    Wire w = new Wire();
    w.rows.put(
        new JSONObject()
            .put("order_id", "cancelled-old")
            .put("uid", "4242")
            .put("audit_status_text", "已取消"));
    try (SchoolSession s = new SchoolSession(record(), w)) {
      assertEquals(1, s.orders(true).length());
    }
  }

  @Test
  public void homeInitializationDoesNotAssumeAnUnusedListField() throws Exception {
    try (SchoolSession s = new SchoolSession(record(), new Wire())) {
      s.initializeHome();
    }
  }

  @Test
  public void schoolRangesNormalizeWhitespaceAndSeconds() {
    assertEquals("08:00-09:00", SchoolSession.normalizedRange(" 8:00:00 – 9:00:00 "));
    assertEquals("", SchoolSession.normalizedRange("25:00-26:00"));
  }
}
