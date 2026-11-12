package com.example.xmu_sports_booking.capture;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.*;
import org.junit.Test;

public class AdbConnectionFailureTest {
  @Test public void wrappedSocketRefusalIsNotReportedAsAnUnknownCaptureFailure() {
    assertEquals("ADB_CONNECTION_PORT_CLOSED", AdbConnectionFailure.code(
        new IOException(new ConnectException("synthetic private connection details"))));
  }
  @Test public void separatesTimeoutTlsAndUnknownFailures() {
    assertEquals("ADB_CONNECTION_TIMEOUT", AdbConnectionFailure.code(new IOException(new SocketTimeoutException())));
    assertEquals("ADB_CONNECTION_TLS_FAILED", AdbConnectionFailure.code(new IOException(new javax.net.ssl.SSLException("synthetic"))));
    assertEquals("ADB_NOT_CONNECTED", AdbConnectionFailure.code(new IOException("synthetic")));
  }
  @Test public void discoveryCannotSelectAServiceOnAnotherDevice() throws Exception {
    assertFalse(LocalAdbDiscovery.localAddress(null));
    assertFalse(LocalAdbDiscovery.localAddress(InetAddress.getByName("0.0.0.0")));
    assertFalse(LocalAdbDiscovery.localAddress(InetAddress.getByName("192.0.2.9")));
    assertTrue(LocalAdbDiscovery.localAddress(InetAddress.getByName("127.0.0.1")));
  }
}
