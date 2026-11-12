package com.example.xmu_sports_booking.local;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReadSessionCacheTest {
  @Test public void reuseNeverExtendsTheOriginalVerificationLease() {
    ReadSessionCache<String> cache = new ReadSessionCache<>(60000);
    cache.put("authorization-v1", "verified-session", 1000);
    assertEquals("verified-session", cache.get("authorization-v1", 2000));
    assertEquals("verified-session", cache.get("authorization-v1", 60999));
    assertNull(cache.get("authorization-v1", 61000));
  }
  @Test public void authorizationReplacementDeletionAndClockRollbackInvalidate() {
    ReadSessionCache<String> cache = new ReadSessionCache<>(60000);
    cache.put("first", "session", 1000);
    assertNull(cache.get("replacement", 1001));
    assertNull(cache.get("first", 1002));
    cache.put("first", "session", 1000);
    assertNull(cache.get(null, 1001));
    cache.put("first", "session", 1000);
    assertNull(cache.get("first", 999));
  }
  @Test public void schoolRejectionClearsTheCachedSession() {
    ReadSessionCache<String> cache = new ReadSessionCache<>(60000);
    cache.put("authorization", "session", 1000);
    cache.clear();
    assertNull(cache.get("authorization", 1001));
  }
}
