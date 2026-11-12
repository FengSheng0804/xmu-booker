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
    assertEquals(90000, window.getSecond() - window.getFirst());
  }

  @Test
  public void crossingMidnightRemainsInsideSameReleaseWindow() throws Exception {
    Pair<Long, Long> window =
        LocalEngine.Companion.executionWindow(
            rule("23:59:00", "00:00:15", 120), LocalDate.parse("2026-11-20"));
    assertEquals(
        Instant.parse("2026-11-19T16:00:15Z").toEpochMilli(), window.getFirst().longValue());
  }

  @Test
  public void outsideOrAtDeadlineIsRejected() {
    assertThrows(
        Exception.class,
        () ->
            LocalEngine.Companion.executionWindow(
                rule("07:00:00", "06:59:59", 120), LocalDate.parse("2026-11-20")));
    assertThrows(
        Exception.class,
        () ->
            LocalEngine.Companion.executionWindow(
                rule("07:00:00", "07:02:00", 120), LocalDate.parse("2026-11-20")));
  }
}
