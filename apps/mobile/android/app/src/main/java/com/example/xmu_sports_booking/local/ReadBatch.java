package com.example.xmu_sports_booking.local;

import java.util.*;
import java.util.concurrent.*;

/** Small, ordered batches for independent read requests only. */
public final class ReadBatch {
  private ReadBatch() {}
  public static <T> List<T> run(List<Callable<T>> calls) throws Exception {
    if (calls.isEmpty()) return Collections.emptyList();
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(2, calls.size()));
    List<Future<T>> pending = new ArrayList<>();
    try {
      for (Callable<T> call : calls) pending.add(pool.submit(call));
      List<T> result = new ArrayList<>();
      for (Future<T> future : pending) {
        try { result.add(future.get()); }
        catch (ExecutionException e) {
          if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
          throw e;
        }
      }
      return result;
    } finally {
      for (Future<T> future : pending) if (!future.isDone()) future.cancel(true);
      pool.shutdownNow();
    }
  }
}
