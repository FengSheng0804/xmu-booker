package com.example.xmu_sports_booking.local;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorRulesTest {
  private JSONObject plan() throws Exception {
    return new JSONObject().put("execution_mode", "MONITOR").put("poll_seconds", 30)
        .put("monitor_start_at", 100000).put("monitor_end_at", 1000000);
  }
  @Test public void absoluteMonitorWindowDoesNotUseReleaseTime() throws Exception {
    JSONObject p = plan().put("release_time", "07:00:00").put("execution_time", "16:00:00");
    assertTrue(MonitorRules.enabled(p));
    assertArrayEquals(new long[]{100000,1000000}, MonitorRules.window(p));
    assertFalse(MonitorRules.enabled(new JSONObject()));
  }
  @Test public void invalidWindowAndAggressivePollingAreRejected() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> MonitorRules.window(plan().put("monitor_end_at", 100000)));
    assertThrows(IllegalArgumentException.class, () -> MonitorRules.window(plan().put("monitor_end_at", 100000L + 73L * 3600000)));
    assertThrows(IllegalArgumentException.class, () -> MonitorRules.interval(plan().put("poll_seconds", 9)));
    assertEquals(10, MonitorRules.interval(plan().put("poll_seconds", 10)));
  }
  @Test public void errorsBackOffAndPollingNeverExtendsTheDeadline() {
    assertEquals(11000, MonitorRules.nextPoll(1000, 1000000, 10, 0));
    assertEquals(21000, MonitorRules.nextPoll(1000, 1000000, 10, 1));
    assertEquals(31000, MonitorRules.nextPoll(1000, 1000000, 30, 0));
    assertEquals(61000, MonitorRules.nextPoll(1000, 1000000, 30, 1));
    assertEquals(601000, MonitorRules.nextPoll(1000, 1000000, 300, 10));
    assertEquals(2000, MonitorRules.nextPoll(1000, 2000, 30, 0));
  }
}
