package com.example.xmu_sports_booking.capture;

import android.content.Context;
import android.os.Build;
import android.util.Base64;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.JSONObject;

/** App-owned ADB key, explicitly paired by the owner. Connections are loopback-only. */
public final class PhoneAdb extends AbsAdbConnectionManager {
  private final PrivateKey privateKey;
  private final Certificate certificate;

  public PhoneAdb(Context context) throws Exception {
    setApi(Build.VERSION.SDK_INT);
    setHostAddress("127.0.0.1");
    setTimeout(8, TimeUnit.SECONDS);
    setThrowOnUnauthorised(true);
    TokenVault vault = new TokenVault(context);
    String stored = vault.loadSecret("adb_identity");
    if (stored != null) {
      JSONObject keys = new JSONObject(stored);
      privateKey =
          KeyFactory.getInstance("RSA")
              .generatePrivate(
                  new PKCS8EncodedKeySpec(
                      Base64.decode(keys.getString("private"), Base64.NO_WRAP)));
      certificate =
          CertificateFactory.getInstance("X.509")
              .generateCertificate(
                  new ByteArrayInputStream(
                      Base64.decode(keys.getString("certificate"), Base64.NO_WRAP)));
    } else {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      java.security.KeyPair pair = generator.generateKeyPair();
      privateKey = pair.getPrivate();
      X500Name subject = new X500Name("CN=XMU Own Sports Capture");
      long now = System.currentTimeMillis();
      certificate =
          new JcaX509CertificateConverter()
              .getCertificate(
                  new JcaX509v3CertificateBuilder(
                          subject,
                          new BigInteger(160, new SecureRandom()).add(BigInteger.ONE),
                          new Date(now - 60000),
                          new Date(now + TimeUnit.DAYS.toMillis(3650)),
                          subject,
                          pair.getPublic())
                      .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                      .build(new JcaContentSignerBuilder("SHA256withRSA").build(privateKey)));
      vault.saveSecret(
          "adb_identity",
          new JSONObject()
              .put("private", Base64.encodeToString(privateKey.getEncoded(), Base64.NO_WRAP))
              .put("certificate", Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP))
              .toString());
    }
  }

  @Override
  protected PrivateKey getPrivateKey() {
    return privateKey;
  }

  @Override
  protected Certificate getCertificate() {
    return certificate;
  }

  @Override
  protected String getDeviceName() {
    return "XmuOwnSportsCapture";
  }

  public boolean pairLocal(int port, String code) throws Exception {
    if (port < 1024 || port > 65535 || code == null || !code.matches("[0-9]{6}"))
      throw new Exception("PAIRING_INPUT_REQUIRED");
    try {
      return pair("127.0.0.1", port, code);
    } catch (java.net.ConnectException e) {
      throw new Exception("PAIRING_PORT_CLOSED");
    } catch (java.net.SocketTimeoutException e) {
      throw new Exception("PAIRING_TIMEOUT");
    } catch (javax.net.ssl.SSLException e) {
      throw new Exception("PAIRING_TLS_FAILED");
    } catch (java.io.IOException e) {
      throw new Exception("PAIRING_CODE_REJECTED");
    } catch (LinkageError e) {
      throw new Exception("PAIRING_CRYPTO_FAILED");
    }
  }

  public void connectLocal(int port) throws Exception {
    if (port < 1024 || port > 65535) throw new Exception("ADB_PORT_REQUIRED");
    if (!isConnected() && !connect("127.0.0.1", port)) throw new Exception("ADB_NOT_CONNECTED");
  }

  AdbStream endpoint(String name) throws Exception {
    if (!name.matches("webview_devtools_remote_[0-9]{1,8}"))
      throw new Exception("INVALID_ENDPOINT");
    return openStream("localabstract:" + name);
  }

  String fixedShell(boolean processList) throws Exception {
    // No arbitrary command parameter is exposed to callers or the Flutter channel.
    try (AdbStream stream =
        openStream(
            processList ? "shell,v2,raw:ps -A -o PID,NAME" : "shell,v2,raw:cat /proc/net/unix")) {
      return ShellReply.read(new TimedInput(stream));
    }
  }

  static final class TimedInput extends AdbPacketInput {
    TimedInput(AdbStream stream) {
      super(
          new Packets() {
            private final InputStream input = stream.openInputStream();

            public boolean closed() {
              return stream.isClosed();
            }

            public int available() throws IOException {
              return input.available();
            }

            public int read(byte[] bytes, int offset, int count) throws IOException {
              return input.read(bytes, offset, count);
            }
          });
    }
  }
}
