package com.example.xmu_sports_booking.capture;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Discover only this phone's TLS connection port. Never connect to an advertised remote host. */
final class LocalAdbDiscovery {
  private LocalAdbDiscovery() {}

  static boolean localAddress(InetAddress host) {
    if (host == null || host.isAnyLocalAddress()) return false;
    try {
      return host.isLoopbackAddress() || NetworkInterface.getByInetAddress(host) != null;
    } catch (java.net.SocketException error) {
      return false;
    }
  }

  @SuppressWarnings("deprecation")
  static int find(Context context) throws InterruptedException {
    NsdManager manager = context.getSystemService(NsdManager.class);
    if (manager == null) return 0;
    CountDownLatch done = new CountDownLatch(1);
    AtomicBoolean closed = new AtomicBoolean();
    AtomicInteger port = new AtomicInteger();
    NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
      public void onDiscoveryStarted(String type) {}
      public void onDiscoveryStopped(String type) {}
      public void onServiceLost(NsdServiceInfo info) {}
      public void onStartDiscoveryFailed(String type, int code) { done.countDown(); }
      public void onStopDiscoveryFailed(String type, int code) {}
      public void onServiceFound(NsdServiceInfo service) {
        if (closed.get()) return;
        try {
          manager.resolveService(service, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo info, int code) {}
            public void onServiceResolved(NsdServiceInfo info) {
              if (!closed.get() && localAddress(info.getHost())
                  && info.getPort() >= 1024 && info.getPort() <= 65535) {
                port.compareAndSet(0, info.getPort());
                done.countDown();
              }
            }
          });
        } catch (RuntimeException ignored) {
          // Discovery is optional; the current port can always be entered manually.
        }
      }
    };
    try {
      manager.discoverServices("_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener);
      done.await(5, TimeUnit.SECONDS);
    } catch (RuntimeException ignored) {
      return 0;
    } finally {
      closed.set(true);
      try { manager.stopServiceDiscovery(listener); } catch (RuntimeException ignored) {}
    }
    return port.get();
  }
}
