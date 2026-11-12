package com.example.xmu_sports_booking.capture;

/** The ADB library wraps socket failures in IOException; classify the bounded cause chain. */
public final class AdbConnectionFailure {
  private AdbConnectionFailure() {}

  public static String code(Throwable error) {
    Throwable current = error;
    for (int i = 0; current != null && i < 8; i++, current = current.getCause()) {
      if (current instanceof java.net.ConnectException) return "ADB_CONNECTION_PORT_CLOSED";
      if (current instanceof java.net.SocketTimeoutException) return "ADB_CONNECTION_TIMEOUT";
      if (current instanceof javax.net.ssl.SSLException) return "ADB_CONNECTION_TLS_FAILED";
      if (current instanceof io.github.muntashirakon.adb.AdbPairingRequiredException)
        return "ADB_PAIRING_REQUIRED";
    }
    return "ADB_NOT_CONNECTED";
  }
}
