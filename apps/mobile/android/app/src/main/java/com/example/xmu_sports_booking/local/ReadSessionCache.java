package com.example.xmu_sports_booking.local;

/** Process-only lease, bound to the exact current authorization and a monotonic clock. */
final class ReadSessionCache<T> {
  private final long ttl;
  private String authorization;
  private T value;
  private long verifiedAt;
  ReadSessionCache(long ttl) { this.ttl = ttl; }

  synchronized T get(String current, long now) {
    if (current == null || !current.equals(authorization) || now < verifiedAt || now - verifiedAt >= ttl)
      clear();
    return value;
  }

  synchronized void put(String current, T next, long now) {
    authorization = current; value = next; verifiedAt = now;
  }

  synchronized void clear() { authorization = null; value = null; verifiedAt = 0; }
}
