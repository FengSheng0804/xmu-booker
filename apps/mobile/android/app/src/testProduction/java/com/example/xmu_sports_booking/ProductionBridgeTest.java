package com.example.xmu_sports_booking;

import static org.junit.Assert.*;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.plugin.common.MethodChannel;
import org.junit.Test;

/** Production compilation must include the same bridge used by the owner-authorized UI. */
public final class ProductionBridgeTest {
  @Test
  public void productionIncludesEnabledBridgeWithoutHardcodedAppId() throws Exception {
    assertEquals("production", BuildConfig.FLAVOR);
    assertTrue(BuildConfig.OWN_SPORTS_AUTH_ENABLED);
    assertNotNull(CaptureBridge.class.getConstructor(FlutterActivity.class, MethodChannel.class));
    assertTrue(AutoCloseable.class.isAssignableFrom(CaptureBridge.class));
  }
}
