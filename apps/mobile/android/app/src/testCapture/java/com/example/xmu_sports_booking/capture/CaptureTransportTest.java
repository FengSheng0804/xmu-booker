package com.example.xmu_sports_booking.capture;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CaptureTransportTest {
  @Test
  public void smallParserReadsKeepTheCompletePacketAfterPeerClose() throws Exception {
    byte[] payload =
        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]".getBytes(StandardCharsets.US_ASCII);
    class Peer implements AdbPacketInput.Packets {
      boolean closed;
      int requested;

      public boolean closed() {
        return closed;
      }

      public int available() {
        return closed ? 0 : payload.length;
      }

      public int read(byte[] bytes, int offset, int count) {
        requested = count;
        int size = Math.min(count, payload.length);
        System.arraycopy(payload, 0, bytes, offset, size);
        closed = true; // Model the remote close immediately following its last packet.
        return size;
      }
    }
    Peer peer = new Peer();
    AdbPacketInput in = new AdbPacketInput(peer);
    ByteArrayOutputStream observed = new ByteArrayOutputStream();
    int next;
    while ((next = in.read()) != -1) observed.write(next);
    assertArrayEquals(payload, observed.toByteArray());
    assertEquals(payload.length, peer.requested);
  }

  @Test
  public void unsignedBytesAreNotMistakenForEndOfStream() throws Exception {
    AdbPacketInput input =
        new AdbPacketInput(
            new AdbPacketInput.Packets() {
              boolean done;

              public boolean closed() {
                return done;
              }

              public int available() {
                return 1;
              }

              public int read(byte[] bytes, int offset, int count) {
                bytes[offset] = (byte) 255;
                done = true;
                return 1;
              }
            });
    assertEquals(255, input.read());
    assertEquals(-1, input.read());
  }

  private static byte[] frame(int channel, byte[] body) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(channel);
    for (int i = 0; i < 4; i++) out.write(body.length >>> (8 * i) & 255);
    out.write(body);
    return out.toByteArray();
  }

  private static byte[] transcript(int exit) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(frame(1, "synthetic process list\n".getBytes(StandardCharsets.UTF_8)));
    out.write(frame(2, "synthetic stderr must be discarded".getBytes(StandardCharsets.UTF_8)));
    out.write(frame(3, new byte[] {(byte) exit}));
    return out.toByteArray();
  }

  @Test
  public void exitPacketEndsCommandWithoutWaitingForStreamEof() throws Exception {
    ByteArrayInputStream input = new ByteArrayInputStream(transcript(0));
    InputStream noEof =
        new InputStream() {
          public int read() throws IOException {
            if (input.available() == 0) throw new IOException("WAITED_FOR_EOF");
            return input.read();
          }

          public int read(byte[] data, int offset, int length) throws IOException {
            if (input.available() == 0) throw new IOException("WAITED_FOR_EOF");
            return input.read(
                data, offset, Math.min(1, length)); // Arbitrarily fragmented transport.
          }
        };
    assertEquals("synthetic process list\n", ShellReply.read(noEof));
  }

  @Test
  public void nonzeroExitCannotBeUsedAsProcessMetadata() throws Exception {
    try {
      ShellReply.read(new ByteArrayInputStream(transcript(1)));
      fail();
    } catch (IOException error) {
      assertEquals("SHELL_COMMAND_FAILED", error.getMessage());
    }
  }

  @Test
  public void truncatedFrameIsRejected() throws Exception {
    try {
      ShellReply.read(new ByteArrayInputStream(new byte[] {1, 5, 0, 0, 0, 1}));
      fail();
    } catch (IOException error) {
      assertEquals("SHELL_REPLY_TRUNCATED", error.getMessage());
    }
  }

  @Test
  public void oversizedFrameIsRejectedBeforeAllocation() throws Exception {
    try {
      ShellReply.read(new ByteArrayInputStream(new byte[] {1, -1, -1, -1, 127}));
      fail();
    } catch (IOException error) {
      assertEquals("SHELL_REPLY_TOO_LARGE", error.getMessage());
    }
  }

  @Test
  public void interruptionCancelsAnIdleTransport() throws Exception {
    AdbPacketInput input =
        new AdbPacketInput(
            new AdbPacketInput.Packets() {
              public boolean closed() {
                return false;
              }

              public int available() {
                return 0;
              }

              public int read(byte[] data, int offset, int count) {
                return 0;
              }
            });
    Thread.currentThread().interrupt();
    try {
      input.read();
      fail();
    } catch (IOException error) {
      assertEquals("CANCELLED", error.getMessage());
    } finally {
      Thread.interrupted();
    }
  }
}
