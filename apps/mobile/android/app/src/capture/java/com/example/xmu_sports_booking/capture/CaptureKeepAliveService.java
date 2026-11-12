package com.example.xmu_sports_booking.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Visible, time-bounded lifetime while the owner switches to WeChat. Never starts at boot. */
public final class CaptureKeepAliveService extends Service {
  private final Handler timer = new Handler(Looper.getMainLooper());

  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel("own_sports_auth", "本人体育授权", NotificationManager.IMPORTANCE_LOW));
    Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    startForeground(
        2802,
        new Notification.Builder(this, "own_sports_auth")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("正在获取本人的体育授权")
            .setContentText("仅查看体育预约页面，点击返回 App 可停止")
            .setContentIntent(open)
            .setOngoing(true)
            .build());
    timer.removeCallbacksAndMessages(null);
    timer.postDelayed(this::stopSelf, 155000);
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    timer.removeCallbacksAndMessages(null);
    stopForeground(STOP_FOREGROUND_REMOVE);
    super.onDestroy();
  }
}
