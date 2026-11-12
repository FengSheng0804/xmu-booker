package com.example.xmu_sports_booking.capture;

import android.util.Base64;
import io.github.muntashirakon.adb.AdbStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local adbd stream client. No TCP listener or external debugging port is opened. */
final class LocalWebView implements AutoCloseable {
  private final AdbStream stream;
  private final InputStream in;
  private final OutputStream out;
  private final SecureRandom random = new SecureRandom();

  LocalWebView(AdbStream stream) throws Exception {
    this.stream = stream;
    in = new PhoneAdb.TimedInput(stream);
    out = stream.openOutputStream();
  }

  private byte[] exact(int length) throws Exception {
    if (length < 0 || length > 2_000_000) throw new Exception("FRAME_TOO_LARGE");
    byte[] bytes = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = in.read(bytes, offset, length - offset);
      if (read < 0) throw new Exception("ENDPOINT_CLOSED");
      offset += read;
    }
    return bytes;
  }

  private String header() throws Exception {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    int a = 0, b = 0, c = 0;
    while (data.size() < 16384) {
      int d = in.read();
      if (d < 0) throw new Exception("ENDPOINT_CLOSED");
      data.write(d);
      if (a == 13 && b == 10 && c == 13 && d == 10) return data.toString("US-ASCII");
      a = b;
      b = c;
      c = d;
    }
    throw new Exception("HEADER_TOO_LARGE");
  }

  JSONArray pages() throws Exception {
    out.write(
        "GET /json/list HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII));
    out.flush();
    String response = header();
    if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200"))
      throw new Exception("ENDPOINT_REJECTED");
    int length = -1;
    for (String line : response.split("\r\n"))
      if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
        length = Integer.parseInt(line.substring(15).trim());
    if (length < 0) throw new Exception("LENGTH_REQUIRED");
    return new JSONArray(new String(exact(length), StandardCharsets.UTF_8));
  }

  private void sendFrame(int opcode, byte[] payload) throws Exception {
    if (payload.length > 65535) throw new Exception("FRAME_TOO_LARGE");
    out.write(128 | opcode);
    if (payload.length < 126) out.write(128 | payload.length);
    else {
      out.write(128 | 126);
      out.write(payload.length >>> 8);
      out.write(payload.length & 255);
    }
    byte[] mask = new byte[4];
    random.nextBytes(mask);
    out.write(mask);
    byte[] masked = payload.clone();
    for (int i = 0; i < masked.length; i++) masked[i] ^= mask[i % 4];
    out.write(masked);
    out.flush();
  }

  String readSportsKey(String path) throws Exception {
    if (!path.matches("/devtools/page/[A-Za-z0-9_.-]{1,200}")) throw new Exception("INVALID_PAGE");
    byte[] key = new byte[16];
    random.nextBytes(key);
    String encodedKey = Base64.encodeToString(key, Base64.NO_WRAP);
    String request =
        "GET "
            + path
            + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Sec-WebSocket-Key: "
            + encodedKey
            + "\r\n\r\n";
    out.write(request.getBytes(StandardCharsets.US_ASCII));
    out.flush();
    String response = header();
    String accept =
        Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest(
                    (encodedKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                        .getBytes(StandardCharsets.US_ASCII)),
            Base64.NO_WRAP);
    if (!response.startsWith("HTTP/1.1 101")) throw new Exception("UPGRADE_REJECTED");
    boolean accepted = false;
    for (String line : response.split("\r\n"))
      if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-accept:"))
        accepted = accept.equals(line.substring(line.indexOf(':') + 1).trim());
    if (!accepted) throw new Exception("UPGRADE_INVALID");
    String expression =
        "(() => {const u=new"
            + " URL(location.href);if(u.protocol!=='https:'||u.hostname!=='xdty.xmu.edu.cn'||!['/bdlp_h5_fitness_test/view/stadium/home.html','/bdlp_h5_fitness_test/view/stadium/choose.html'].includes(u.pathname))return"
            + " null;return localStorage.getItem('hrefstr');})()";
    JSONObject command =
        new JSONObject()
            .put("id", 1)
            .put("method", "Runtime.evaluate")
            .put(
                "params",
                new JSONObject()
                    .put("expression", expression)
                    .put("returnByValue", true)
                    .put("silent", true));
    sendFrame(1, command.toString().getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream message = new ByteArrayOutputStream();
    for (int index = 0; index < 40; index++) {
      int first = exact(1)[0] & 255, second = exact(1)[0] & 255;
      if ((first & 112) != 0 || (second & 128) != 0) throw new Exception("FRAME_INVALID");
      int opcode = first & 15;
      long size = second & 127;
      if (size == 126) {
        byte[] raw = exact(2);
        size = ((raw[0] & 255) << 8) | (raw[1] & 255);
      } else if (size == 127) {
        size = 0;
        for (byte b : exact(8)) {
          if (size > 2_000_000) throw new Exception("FRAME_TOO_LARGE");
          size = size * 256 + (b & 255);
        }
      }
      if (size > 2_000_000) throw new Exception("FRAME_TOO_LARGE");
      byte[] payload = exact((int) size);
      if (opcode == 8) throw new Exception("ENDPOINT_CLOSED");
      if (opcode == 9) {
        if (payload.length > 125) throw new Exception("FRAME_INVALID");
        sendFrame(10, payload);
        continue;
      }
      if (opcode == 10) continue;
      if (opcode != 1 && opcode != 0) throw new Exception("FRAME_INVALID");
      if (message.size() + payload.length > 2_000_000) throw new Exception("MESSAGE_TOO_LARGE");
      message.write(payload);
      if ((first & 128) == 0) continue;
      JSONObject result = new JSONObject(message.toString("UTF-8"));
      message.reset();
      if (result.optInt("id") != 1) continue;
      JSONObject outer = result.optJSONObject("result");
      JSONObject value = outer == null ? null : outer.optJSONObject("result");
      if (value == null || !"string".equals(value.optString("type"))) return null;
      String query = value.optString("value", "");
      return query.length() <= 16384 ? query : null;
    }
    return null;
  }

  public void close() throws java.io.IOException {
    stream.close();
  }
}
