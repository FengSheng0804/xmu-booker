package com.example.xmu_sports_booking.capture;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same scoped sports read through a user-paired local system ADB transport. */
public final class SportsCaptureEngine {
  public volatile String stage = "READY";
  private final AtomicBoolean cancelled = new AtomicBoolean();

  public void cancel() {
    cancelled.set(true);
  }

  private Set<String> endpoints(PhoneAdb adb) throws Exception {
    Set<String> pids = new HashSet<>(), names = new HashSet<>();
    for (String line : adb.fixedShell(true).split("\n")) {
      String[] pieces = line.trim().split("\\s+");
      if (pieces.length == 2
          && (pieces[1].equals("com.tencent.mm") || pieces[1].startsWith("com.tencent.mm:")))
        pids.add(pieces[0]);
    }
    Pattern pattern = Pattern.compile(" @(webview_devtools_remote_([0-9]+))$");
    for (String line : adb.fixedShell(false).split("\n")) {
      Matcher match = pattern.matcher(line.trim());
      if (match.find() && pids.contains(match.group(2))) names.add(match.group(1));
    }
    return names;
  }

  public boolean debugVisible(PhoneAdb adb) throws Exception {
    return !endpoints(adb).isEmpty();
  }

  public String capture(PhoneAdb adb, String expectedStudent) throws Exception {
    if (expectedStudent == null || !expectedStudent.matches("[0-9]{6,24}"))
      throw new Exception("ACCOUNT_REQUIRED");
    long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
    boolean foundEndpoint = false, mismatch = false;
    do {
      stage = "FINDING_WECHAT_PAGE";
      for (String endpoint : endpoints(adb)) {
        foundEndpoint = true;
        try {
          JSONArray pages;
          try (LocalWebView client = new LocalWebView(adb.endpoint(endpoint))) {
            pages = client.pages();
          }
          for (int i = 0; i < pages.length(); i++) {
            if (cancelled.get()) throw new Exception("CANCELLED");
            JSONObject page = pages.optJSONObject(i);
            if (page == null || !SportsProtocol.sportsPage(page.optString("url"))) continue;
            stage = "READING_SPORTS_AUTHORIZATION";
            URI ws = new URI(page.optString("webSocketDebuggerUrl"));
            if (!"ws".equals(ws.getScheme())
                || !("localhost".equals(ws.getHost()) || "127.0.0.1".equals(ws.getHost()))
                || ws.getUserInfo() != null
                || ws.getQuery() != null
                || ws.getFragment() != null) continue;
            String query;
            try (LocalWebView client = new LocalWebView(adb.endpoint(endpoint))) {
              query = client.readSportsKey(ws.getPath());
            }
            if (query == null) continue;
            try {
              SportsProtocol.parse(query, expectedStudent);
            } catch (Exception error) {
              if ("OWNER_MISMATCH".equals(error.getMessage())) mismatch = true;
              continue;
            }
            if (!cancelled.get()) {
              stage = "OWN_CONTEXT_READ";
              return query;
            }
          }
        } catch (Exception error) {
          if (cancelled.get() || !adb.isConnected()) throw error;
        }
      }
      Thread.sleep(1200);
    } while (!cancelled.get() && android.os.SystemClock.elapsedRealtime() < deadline);
    throw new Exception(
        cancelled.get()
            ? "CANCELLED"
            : mismatch
                ? "OWNER_MISMATCH"
                : foundEndpoint ? "SPORTS_PAGE_NOT_FOUND" : "WECHAT_DEBUG_NOT_ENABLED");
  }
}
