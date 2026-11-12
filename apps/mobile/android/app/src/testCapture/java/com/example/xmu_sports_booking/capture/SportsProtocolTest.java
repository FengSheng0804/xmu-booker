package com.example.xmu_sports_booking.capture;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class SportsProtocolTest {
  @Test public void identifiesEachRejectedReadStage() throws Exception {
    String[] paths = {"/v3/api.php/WpLogin/UserInfo",
        SportsProtocol.PREFIX + "/index/index/checklogin",
        SportsProtocol.PREFIX + "/index/stadium/mySubscribe"};
    String[] codes = {"READ_CHECK_IDENTITY_REJECTED", "READ_CHECK_LOGIN_REJECTED", "READ_CHECK_ORDERS_REJECTED"};
    for (int i = 0; i < paths.length; i++) {
      final String path = paths[i];
      SportsProtocol.ReadRejectedException error = assertThrows(SportsProtocol.ReadRejectedException.class,
          () -> SportsProtocol.checkResponse(path, new JSONObject("{status:0,info:'登录信息失效'}"), ""));
      assertEquals(codes[i], error.getMessage());
      assertEquals(0, error.status);
      assertTrue(error.description().contains("待核对记录会保留"));
    }
  }

  @Test public void schoolRejectionNeverExposesRequestSecretsOrResponseDump() throws Exception {
    JSONObject response = new JSONObject().put("status", 0)
        .put("info", "<b>拒绝</b> abc-secret token=hidden-token uid=42424242 https://example.test/private")
        .put("data", new JSONObject().put("token", "not-to-be-dumped"));
    SportsProtocol.ReadRejectedException error = assertThrows(SportsProtocol.ReadRejectedException.class,
        () -> SportsProtocol.checkResponse(SportsProtocol.PREFIX + "/index/index/checklogin", response,
            "token=abc-secret&uid=42424242"));
    String text = error.description();
    for (String secret : new String[] {"abc-secret", "hidden-token", "42424242", "example.test", "not-to-be-dumped", "<b>"})
      assertFalse(text.contains(secret));
  }

  @Test public void successPassesAndMissingStatusRemainsRejected() throws Exception {
    SportsProtocol.checkResponse("/v3/api.php/WpLogin/UserInfo", new JSONObject("{status:1}"), "");
    SportsProtocol.ReadRejectedException error = assertThrows(SportsProtocol.ReadRejectedException.class,
        () -> SportsProtocol.checkResponse("/v3/api.php/WpLogin/UserInfo", new JSONObject(), ""));
    assertEquals(-1, error.status);
    assertEquals("学校未提供详细原因", error.schoolMessage);
  }
}
