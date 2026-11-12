package com.example.xmu_sports_booking.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

/** The single durable owner of plans, scheduling and submission receipts. No credentials here. */
@Database(
    entities = {
      LocalDatabase.Plan.class,
      LocalDatabase.Task.class,
      LocalDatabase.Cache.class,
      LocalDatabase.Message.class
    },
    version = 1,
    exportSchema = true)
public abstract class LocalDatabase extends RoomDatabase {
  private static volatile LocalDatabase instance;

  public static LocalDatabase get(Context context) {
    if (instance == null)
      synchronized (LocalDatabase.class) {
        if (instance == null)
          instance =
              Room.databaseBuilder(
                      context.getApplicationContext(), LocalDatabase.class, "local-booking.db")
                  .build();
      }
    return instance;
  }

  public abstract Store store();

  @Entity(tableName = "plans")
  public static class Plan {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String json = "{}";
    public boolean enabled;
    public long updatedAt;
  }

  @Entity(
      tableName = "tasks",
      indices = {
        @Index(
            value = {"planId", "date"},
            unique = true)
      })
  public static class Task {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String planId = "";
    @NonNull public String date = "";
    @NonNull public String target = "{}";
    @NonNull public String state = "PENDING";
    @NonNull public String receipt = "{}";
    @NonNull public String reason = "";
    public long prepareAt;
    public long runAt;
    public long deadline;
    public long updatedAt;
  }

  @Entity(tableName = "cache")
  public static class Cache {
    @PrimaryKey @NonNull public String key = "";
    @NonNull public String json = "[]";
    public long updatedAt;
  }

  @Entity(tableName = "messages")
  public static class Message {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String text = "";
    public long createdAt;
  }

  @Dao
  public interface Store {
    @Query("SELECT * FROM plans ORDER BY updatedAt DESC")
    List<Plan> plans();

    @Query("SELECT * FROM plans WHERE id=:id")
    Plan plan(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void savePlan(Plan plan);

    @Query("DELETE FROM plans WHERE id=:id")
    void deletePlan(String id);

    @Query("SELECT * FROM tasks ORDER BY runAt DESC LIMIT 500")
    List<Task> tasks();

    @Query("SELECT * FROM tasks WHERE date=:date AND state IN ('SENT','UNKNOWN','CLAIMED')")
    List<Task> unresolved(String date);

    @Query("SELECT * FROM tasks WHERE state IN ('SENT','UNKNOWN') ORDER BY runAt DESC")
    List<Task> pendingReconciliation();

    @Query("SELECT COUNT(*) FROM tasks WHERE planId=:planId AND state IN ('CLAIMED','SENT')")
    int inFlightForPlan(String planId);

    @Query("SELECT * FROM tasks WHERE state='PENDING' AND prepareAt<=:now")
    List<Task> due(long now);

    @Query("SELECT * FROM tasks WHERE id=:id")
    Task task(String id);

    @Query("SELECT * FROM tasks WHERE planId=:planId AND date=:date LIMIT 1")
    Task taskForDate(String planId, String date);

    @Query("SELECT * FROM tasks WHERE planId=:planId AND state NOT IN ('PENDING','CLAIMED','CANCELLED') ORDER BY updatedAt DESC LIMIT 1")
    Task latestResult(String planId);

    @Query("SELECT * FROM tasks WHERE state='PENDING' ORDER BY prepareAt LIMIT 1")
    Task next();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long addTask(Task task);

    @Query(
        "UPDATE tasks SET target=:target, prepareAt=:prepareAt, runAt=:runAt, deadline=:deadline,"
            + " state='PENDING', reason='', updatedAt=:now WHERE planId=:planId AND date=:date AND"
            + " state IN ('PENDING','CANCELLED')")
    void refreshUnsent(
        String planId,
        String date,
        String target,
        long prepareAt,
        long runAt,
        long deadline,
        long now);

    @Query(
        "UPDATE tasks SET state=:state, receipt=:receipt, reason=:reason, updatedAt=:now WHERE"
            + " id=:id")
    void finish(String id, String state, String receipt, String reason, long now);

    @Query(
        "UPDATE tasks SET state='CLAIMED', receipt=:claim, updatedAt=:now WHERE id=:id AND"
            + " state='PENDING'")
    int claim(String id, long now, String claim);

    @Query(
        "UPDATE tasks SET state='SENT', receipt=:receipt, updatedAt=:now WHERE id=:id AND"
            + " state='CLAIMED' AND receipt=:claim")
    int markSent(String id, String receipt, long now, String claim);

    @Query(
        "UPDATE tasks SET state='CANCELLED', reason='计划已停用', updatedAt=:now WHERE planId=:planId"
            + " AND state IN ('PENDING','CLAIMED')")
    void cancelPending(String planId, long now);

    @Query(
        "UPDATE tasks SET state='UNKNOWN', reason='执行曾中断，必须先查单', updatedAt=:now WHERE state IN"
            + " ('CLAIMED','SENT')")
    void recover(long now);

    @Query("SELECT * FROM cache WHERE `key`=:key")
    Cache cached(String key);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void cache(Cache row);

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT 200")
    List<Message> messages();

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void message(Message row);

    @Query("DELETE FROM plans")
    void clearPlans();

    @Query("DELETE FROM tasks")
    void clearTasks();

    @Query("DELETE FROM cache")
    void clearCache();

    @Query("DELETE FROM messages")
    void clearMessages();
  }
}
