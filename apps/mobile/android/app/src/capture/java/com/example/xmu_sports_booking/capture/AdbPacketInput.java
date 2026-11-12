package com.example.xmu_sports_booking.capture;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/** Drain each available ADB packet before exposing small reads to an HTTP parser. */
class AdbPacketInput extends InputStream {
  interface Packets {
    boolean closed();

    int available() throws IOException;

    int read(byte[] bytes, int offset, int count) throws IOException;
  }

  private final Packets packets;
  private byte[] buffered = new byte[0];
  private int position;

  AdbPacketInput(Packets packets) {
    this.packets = packets;
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
  }

  @Override
  public int read(byte[] bytes, int offset, int count) throws IOException {
    if (offset < 0 || count < 0 || offset > bytes.length - count)
      throw new IndexOutOfBoundsException();
    if (count == 0) return 0;
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (position == buffered.length) {
      if (packets.closed()) return -1;
      try {
        int available = packets.available();
        if (available > 2_000_000) throw new IOException("PACKET_TOO_LARGE");
        if (available > 0) {
          byte[] next = new byte[available];
          int received = packets.read(next, 0, next.length);
          if (received > 0) {
            buffered = received == next.length ? next : java.util.Arrays.copyOf(next, received);
            position = 0;
            break;
          }
          if (received == -1) return -1;
        }
      } catch (IOException error) {
        if (packets.closed()) return -1;
        throw error;
      }
      if (System.nanoTime() >= until) throw new SocketTimeoutException("LOCAL_READ_TIMEOUT");
      try {
        Thread.sleep(10);
      } catch (InterruptedException error) {
        throw new IOException("CANCELLED");
      }
    }
    int copied = Math.min(count, buffered.length - position);
    System.arraycopy(buffered, position, bytes, offset, copied);
    position += copied;
    return copied;
  }
}
