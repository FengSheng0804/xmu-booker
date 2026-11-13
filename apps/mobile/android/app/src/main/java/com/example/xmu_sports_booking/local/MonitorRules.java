package com.example.xmu_sports_booking.local;

import org.json.JSONObject;

public final class MonitorRules {
  private MonitorRules() {}
  public static boolean enabled(JSONObject plan) { return "MONITOR".equals(plan.optString("execution_mode")); }
  public static int interval(JSONObject plan) {
    int seconds = plan.optInt("poll_seconds", 30);
    if (seconds < 10 || seconds > 600) throw new IllegalArgumentException("MONITOR_INTERVAL_INVALID");
    return seconds;
  }
  public static long[] window(JSONObject plan) {
    long start = plan.optLong("monitor_start_at", 0), end = plan.optLong("monitor_end_at", 0);
    if (start <= 0 || end <= start || end - start > 72L * 3600 * 1000)
      throw new IllegalArgumentException("MONITOR_WINDOW_INVALID");
    interval(plan);
    return new long[] { start, end };
  }
  public static long nextPoll(long now, long deadline, int seconds, int consecutiveErrors) {
    if (seconds < 10 || seconds > 600) throw new IllegalArgumentException("MONITOR_INTERVAL_INVALID");
    long delay = Math.min(600L, seconds * (1L << Math.min(4, Math.max(0, consecutiveErrors)))) * 1000;
    return Math.min(deadline, now + delay);
  }
}
