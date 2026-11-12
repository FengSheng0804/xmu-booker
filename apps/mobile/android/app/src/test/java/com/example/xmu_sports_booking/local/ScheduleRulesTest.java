package com.example.xmu_sports_booking.local;

import static org.junit.Assert.*;

import java.time.Instant;
import java.time.LocalDate;
import kotlin.Pair;
import org.json.JSONObject;
import org.junit.Test;

public class ScheduleRulesTest {
  private JSONObject rule(String open, String execute, int seconds) throws Exception {
    return new JSONObject()
        .put("days_before", 1)
        .put("release_time", open)
        .put("execution_time", execute)
        .put("window_seconds", seconds);
  }

  @Test
  public void releaseUsesBeijingAndPreviousDay() throws Exception {
    Pair<Long, Long> window =
        LocalEngine.Companion.executionWindow(
            rule("07:00:00", "07:00:30", 120), LocalDate.parse("2026-11-20"));
    assertEquals(
        Instant.parse("2026-11-18T23:00:30Z").toEpochMilli(), window.getFirst().longValue());
    assertEquals(120000, window.getSecond() - window.getFirst());
  }

  @Test
  public void releaseDoesNotShiftExecutionToAnotherDate() throws Exception {
    Pair<Long, Long> window =
        LocalEngine.Companion.executionWindow(
            rule("23:59:00", "00:00:15", 120), LocalDate.parse("2026-11-20"));
    assertEquals(
        Instant.parse("2026-11-18T16:00:15Z").toEpochMilli(), window.getFirst().longValue());
    assertEquals(120000, window.getSecond() - window.getFirst());
  }

  @Test
  public void afternoonExecutionAndWindowAreIndependentOfRelease() throws Exception {
    Pair<Long, Long> window = LocalEngine.Companion.executionWindow(
        rule("07:00:00", "16:53:40", 120), LocalDate.parse("2026-11-20"));
    assertEquals(Instant.parse("2026-11-19T08:53:40Z").toEpochMilli(), window.getFirst().longValue());
    assertEquals(Instant.parse("2026-11-19T08:55:40Z").toEpochMilli(), window.getSecond().longValue());
    assertEquals(window, LocalEngine.Companion.executionWindow(
        rule("18:00:00", "16:53:40", 120), LocalDate.parse("2026-11-20")));
  }

  @Test
  public void executionWindowCanCrossMidnight() throws Exception {
    Pair<Long, Long> window = LocalEngine.Companion.executionWindow(
        rule("07:00:00", "23:59:30", 120), LocalDate.parse("2026-11-20"));
    assertEquals(Instant.parse("2026-11-19T16:01:30Z").toEpochMilli(), window.getSecond().longValue());
  }

  @Test
  public void invalidWindowDurationIsRejected() {
    for (int seconds : new int[] {0, 14, 3601}) {
      assertThrows(Exception.class, () -> LocalEngine.Companion.executionWindow(
          rule("07:00:00", "16:53:40", seconds), LocalDate.parse("2026-11-20")));
    }
  }

  @Test
  public void singlePlansFinishButWeeklySchedulesRemainEnabled() throws Exception {
    JSONObject single = new JSONObject().put("repeat", "ONCE");
    JSONObject weekly = new JSONObject().put("repeat", "WEEKLY");
    for (String state : new String[] {"SUCCEEDED", "FAILED", "EXPIRED", "UNKNOWN", "PAYMENT_REQUIRED"}) {
      assertTrue(LocalEngine.Companion.singlePlanFinished(single, state));
      assertFalse(LocalEngine.Companion.singlePlanFinished(weekly, state));
    }
    for (String state : new String[] {"PENDING", "CLAIMED", "CANCELLED"})
      assertFalse(LocalEngine.Companion.singlePlanFinished(single, state));
  }

  @Test
  public void reconciliationScopeDoesNotMixCampusesOrOwnersWithSameVenueId() throws Exception {
    JSONObject target = new JSONObject().put("venue_id", "10").put("stadium_id", "12")
        .put("category_id", "6").put("owner_digest", "owner-a");
    assertTrue(LocalEngine.Companion.sameBookingScope(target, new JSONObject(target.toString())));
    for (String key : new String[] {"stadium_id", "category_id", "owner_digest"}) {
      assertFalse(LocalEngine.Companion.sameBookingScope(target,
          new JSONObject(target.toString()).put(key, "different")));
    }
    assertTrue(LocalEngine.Companion.sameBookingScope(target,
        new JSONObject().put("venue_id", "10")));
  }

  @Test
  public void fallbackDoesNotDuplicateLiveStadiumWithDifferentVenueId() throws Exception {
    org.json.JSONArray live = new org.json.JSONArray(
        "[{stadium_id:16,venue_id:88,category_id:8},{stadium_id:16,venue_id:88,category_id:8}]");
    org.json.JSONArray fallback = new org.json.JSONArray(
        "[{stadium_id:16,venue_id:14,category_id:8},{stadium_id:6,venue_id:6,category_id:8}]");
    org.json.JSONArray result = LocalEngine.Companion.mergeCatalog(live, fallback);
    assertEquals(2, result.length());
    assertEquals("88", result.getJSONObject(0).optString("venue_id"));
    assertEquals("6", result.getJSONObject(1).optString("stadium_id"));
    assertEquals(2, LocalEngine.Companion.mergeCatalog(new org.json.JSONArray(), fallback).length());
  }
}
