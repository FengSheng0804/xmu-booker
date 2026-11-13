package com.example.xmu_sports_booking.local;

import java.net.*;
import javax.net.ssl.SSLException;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutionFailureTest {
  @Test public void transportFailuresAreClassifiedThroughTheirCause() {
    assertEquals("NETWORK_TIMEOUT", ExecutionFailure.code(new RuntimeException(new SocketTimeoutException("secret URL"))));
    assertEquals("NETWORK_UNAVAILABLE", ExecutionFailure.code(new UnknownHostException("secret host")));
    assertEquals("TLS_FAILED", ExecutionFailure.code(new SSLException("secret certificate")));
    assertTrue(ExecutionFailure.description(new SocketTimeoutException("private payload"), "READ_BASELINE_ORDERS").contains("读取本人订单：网络请求超时"));
  }
  @Test public void parsingAndInterruptionHaveSpecificCodesWhileExplicitGuardsSurvive() {
    assertEquals("SCHOOL_RESPONSE_INVALID", ExecutionFailure.code(new JSONException("private response")));
    assertEquals("SCHOOL_RESPONSE_INVALID", ExecutionFailure.code(new NumberFormatException("private number")));
    assertEquals("EXECUTION_INTERRUPTED", ExecutionFailure.code(new InterruptedException("private data")));
    assertEquals("NO_AVAILABLE_TARGET", ExecutionFailure.description(new Exception("NO_AVAILABLE_TARGET"), "SELECT_SLOT"));
  }
  @Test public void persistedDiagnosticsNeverIncludeExceptionMessagesOrRequestValues() throws Exception {
    Exception error = new Exception("token=synthetic-secret&uid=12345", new Exception("private authorization"));
    error.setStackTrace(new StackTraceElement[] {
      new StackTraceElement("com.example.xmu_sports_booking.local.SchoolSession", "inventory", "private-path.java", 123)
    });
    JSONObject diagnostic = ExecutionFailure.diagnostic(error, "READ_INVENTORY");
    assertEquals("READ_INVENTORY", diagnostic.getString("phase"));
    assertEquals("SchoolSession.inventory:123", diagnostic.getString("site"));
    assertEquals("LOCAL_OPERATION_FAILED", diagnostic.getString("code"));
    assertFalse(diagnostic.toString().contains("secret"));
    assertFalse(diagnostic.toString().contains("12345"));
    assertFalse(diagnostic.toString().contains("private"));
    assertFalse(ExecutionFailure.description(error, "READ_INVENTORY").contains("synthetic"));
    assertEquals("LOCAL_OPERATION_FAILED", ExecutionFailure.diagnostic(new Exception("SECRET_CANARY"), "READ_INVENTORY").getString("code"));
  }
}
