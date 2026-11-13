package com.example.xmu_sports_booking.local;

import java.net.*;
import javax.net.ssl.SSLException;
import org.json.JSONException;
import org.json.JSONObject;

/** A diagnostic contains types and source locations, never exception messages or request data. */
public final class ExecutionFailure {
  private ExecutionFailure() {}
  private static final java.util.Set<String> GUARDS = java.util.Set.of(
      "AMBIGUOUS_ORDERS", "AUTHORIZATION_CHANGED", "AUTH_REQUIRED", "CAPTCHA_IMAGE_INVALID",
      "EXECUTION_STOPPED", "EXTRA_INPUT_REQUIRED", "GROUP_UNSUPPORTED", "IDENTITY_CONTEXT_MISMATCH",
      "INCOMPLETE_ORDER_SCAN", "INVALID_CONTEXT", "INVALID_PLAN", "INVALID_SESSION", "INVALID_SIGNATURE",
      "INVALID_TARGET", "INVALID_TASK", "MODEL_HASH_MISMATCH", "MODEL_OUTPUT_INVALID", "NON_NATIVE_CONTEXT",
      "NO_AVAILABLE_TARGET", "ORDERS_OWNER_MISMATCH", "ORDERS_SHAPE_INVALID", "ORDER_DETAILS_EMPTY",
      "ORDER_DETAILS_IDENTITY_MISMATCH", "ORDER_ID_INVALID", "ORDER_METADATA_MISMATCH", "ORDER_SHAPE",
      "OWNER_MISMATCH", "PENDING_RECONCILIATION", "PREPARE_ONCE", "READ_CHECK_HTTP_FAILED",
      "READ_CHECK_IDENTITY_REJECTED", "READ_CHECK_LOGIN_REJECTED", "READ_CHECK_ORDERS_REJECTED",
      "READ_CHECK_TOO_LARGE", "READ_ROUTE_REFUSED", "RESPONSE_TOO_LARGE", "ROUTE_REFUSED",
      "SCHOOL_HTTP_FAILED", "SCHOOL_REJECTED", "SCOPED_SESSION_MISSING", "SESSION_CHANGED", "SLOT_SHAPE",
      "SUBMIT_ALREADY_CLAIMED", "SUBMIT_REFUSED", "VENUE_MAPPING_CHANGED", "VENUE_MISMATCH", "VENUE_UNAVAILABLE");

  public static String code(Throwable error) {
    String explicit = error.getMessage();
    if (GUARDS.contains(explicit == null ? "" : explicit)) return explicit;
    int depth = 0;
    for (Throwable cause = error; cause != null && depth++ < 8; cause = cause.getCause()) {
      if (cause instanceof SocketTimeoutException) return "NETWORK_TIMEOUT";
      if (cause instanceof UnknownHostException || cause instanceof SocketException)
        return "NETWORK_UNAVAILABLE";
      if (cause instanceof SSLException) return "TLS_FAILED";
      if (cause instanceof JSONException || cause instanceof NumberFormatException) return "SCHOOL_RESPONSE_INVALID";
      if (cause instanceof InterruptedException) return "EXECUTION_INTERRUPTED";
      if (cause.getClass().getName().startsWith("ai.onnxruntime.")) return "MODEL_RUNTIME_UNAVAILABLE";
      if (cause.getCause() == cause) break;
    }
    return "LOCAL_OPERATION_FAILED";
  }

  public static String phaseName(String phase) {
    switch (phase) {
      case "VERIFY_AUTHORIZATION": return "核对学校授权";
      case "READ_BASELINE_ORDERS": return "读取本人订单";
      case "WAIT_EXECUTION": return "等待计划执行时间";
      case "READ_INVENTORY": return "查询学校时段与余量";
      case "SELECT_SLOT": return "选择候选时段";
      case "PREPARE_BOOKING": return "校验时段并获取验证码";
      case "RECOGNIZE_CAPTCHA": return "识别验证码";
      case "WAIT_SUBMISSION": return "等待提交间隔";
      case "SUBMIT_BOOKING": return "提交预约";
      case "CHECK_RESULT": return "核对预约结果";
      default: return "检查执行条件";
    }
  }

  public static String description(Throwable error, String phase) {
    String why;
    switch (code(error)) {
      case "NETWORK_TIMEOUT": why = "网络请求超时，请检查网络和学校服务后再安排计划"; break;
      case "NETWORK_UNAVAILABLE": why = "无法连接学校服务，请检查网络"; break;
      case "TLS_FAILED": why = "学校连接安全校验失败"; break;
      case "SCHOOL_RESPONSE_INVALID": why = "学校返回的数据格式异常"; break;
      case "MODEL_RUNTIME_UNAVAILABLE": why = "本机验证码识别模型运行失败"; break;
      case "EXECUTION_INTERRUPTED": why = "后台执行被中断"; break;
      case "LOCAL_OPERATION_FAILED": why = "本机执行异常（" + error.getClass().getSimpleName() + "）"; break;
      default: return code(error);
    }
    return phaseName(phase) + "：" + why;
  }

  public static JSONObject diagnostic(Throwable error, String phase) {
    JSONObject result = new JSONObject();
    try {
      result.put("phase", phase).put("code", code(error))
          .put("exception_type", error.getClass().getName())
          .put("cause_type", error.getCause() == null ? "" : error.getCause().getClass().getName());
      for (StackTraceElement site : error.getStackTrace())
        if (site.getClassName().startsWith("com.example.xmu_sports_booking.")) {
          result.put("site", site.getClassName().substring(site.getClassName().lastIndexOf('.') + 1)
              + "." + site.getMethodName() + ":" + site.getLineNumber());
          break;
        }
    } catch (JSONException ignored) { /* Fixed primitive values only. */ }
    return result;
  }
}
