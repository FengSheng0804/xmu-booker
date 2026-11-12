package com.example.xmu_sports_booking.capture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Android shell-v2 framing: read through EXIT, never wait for an ambiguous stream EOF. */
final class ShellReply {
  private static byte[] exact(InputStream in, int size) throws IOException {
    byte[] data = new byte[size];
    int offset = 0;
    while (offset < size) {
      int count = in.read(data, offset, size - offset);
      if (count < 0) throw new IOException("SHELL_REPLY_TRUNCATED");
      offset += count;
    }
    return data;
  }

  static String read(InputStream in) throws IOException {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    int total = 0;
    while (true) {
      byte[] header = exact(in, 5);
      int channel = header[0] & 255;
      long length = 0;
      for (int i = 4; i >= 1; i--) length = (length << 8) | (header[i] & 255);
      if (length > 2_000_000 || (total += (int) length) > 2_000_000)
        throw new IOException("SHELL_REPLY_TOO_LARGE");
      byte[] data = exact(in, (int) length);
      if (channel == 1) stdout.write(data);
      else if (channel == 2) {
        /* Stderr is not process metadata and is never logged. */
      } else if (channel == 3) {
        if (data.length != 1 || data[0] != 0) throw new IOException("SHELL_COMMAND_FAILED");
        return stdout.toString("UTF-8");
      } else throw new IOException("SHELL_CHANNEL_UNSUPPORTED");
    }
  }
}
