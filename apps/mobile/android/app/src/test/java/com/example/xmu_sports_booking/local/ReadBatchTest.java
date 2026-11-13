package com.example.xmu_sports_booking.local;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReadBatchTest {
  @Test public void atMostTwoReadsRunConcurrentlyAndResultsKeepInputOrder() throws Exception {
    AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
    CountDownLatch pair = new CountDownLatch(2);
    List<Callable<Integer>> calls = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      final int index = i;
      calls.add(() -> {
        int count = active.incrementAndGet(); peak.accumulateAndGet(count, Math::max);
        try {
          pair.countDown(); assertTrue(pair.await(2, TimeUnit.SECONDS));
          return index;
        } finally { active.decrementAndGet(); }
      });
    }
    assertEquals(Arrays.asList(0,1,2,3,4,5), ReadBatch.run(calls));
    assertEquals(2, peak.get());
  }
  @Test public void failuresKeepTheirOriginalType() {
    assertThrows(java.net.SocketTimeoutException.class, () -> ReadBatch.run(Arrays.asList(
        () -> { throw new java.net.SocketTimeoutException(); }, () -> "other")));
  }
}
