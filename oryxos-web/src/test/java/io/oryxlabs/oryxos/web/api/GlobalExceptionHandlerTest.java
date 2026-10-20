package io.oryxlabs.oryxos.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 统一异常出口的判卷（contracts/rest-endpoints.md §6 / data-model.md §四 / §五）。
 *
 * <p>三条守点：① 每类异常映射到约定状态码；② 响应体都是{@link ApiResponse}信封且{@code data} 恒为 {@code null}；③ 500
 * 响应体<b>不含</b>内部异常描述（FR-005）。
 *
 * <p>用探针控制器主动抛异常，而不是去调真实控制器——这里判的是「出口怎么翻译异常」，与谁抛的无关。
 */
@WebMvcTest(controllers = ProbeController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("成功响应与错误响应共用同一个信封（FR-005）")
  void successAndError_shareSameEnvelopeShape() throws Exception {
    mockMvc
        .perform(get("/probe/ok"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.message").value("ok"))
        .andExpect(jsonPath("$.data").value("fine"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  @DisplayName("IllegalArgumentException → 400，既有映射不回退")
  void illegalArgument_mapsTo400() throws Exception {
    mockMvc
        .perform(get("/probe/illegal-argument"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("bad input"))
        .andExpect(jsonPath("$.data").value(nullValue()));
  }

  @Test
  @DisplayName("NoResourceFoundException → 404，既有映射不回退")
  void noResourceFound_mapsTo404() throws Exception {
    mockMvc
        .perform(get("/probe/no-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message", containsString("/missing")))
        .andExpect(jsonPath("$.data").value(nullValue()));
  }

  @Test
  @DisplayName("会话不存在 → 404，话术逐字 session not found: <id>")
  void sessionNotFound_mapsTo404() throws Exception {
    mockMvc
        .perform(get("/probe/session-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("session not found: web:alice:daily-weather"))
        .andExpect(jsonPath("$.data").value(nullValue()));
  }

  @Test
  @DisplayName("Agent 不存在 → 404（不是 503），话术逐字 agent not found: <name>")
  void profileNotFound_mapsTo404() throws Exception {
    mockMvc
        .perform(get("/probe/profile-not-found"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("agent not found: ghost"))
        .andExpect(jsonPath("$.data").value(nullValue()));
  }

  @Test
  @DisplayName("Provider 调用失败 → 503，话术只带厂商名、不带对端报错原文")
  void providerUnavailable_mapsTo503() throws Exception {
    mockMvc
        .perform(get("/probe/provider-unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(jsonPath("$.message").value("provider unavailable: deepseek"))
        .andExpect(jsonPath("$.data").value(nullValue()))
        .andExpect(content().string(not(containsString("connection reset by peer"))));
  }

  @Test
  @DisplayName("Agent 调用超时 → 504，话术逐字 agent invocation timed out")
  void agentTimeout_mapsTo504() throws Exception {
    mockMvc
        .perform(get("/probe/timeout"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.code").value(504))
        .andExpect(jsonPath("$.message").value("agent invocation timed out"))
        .andExpect(jsonPath("$.data").value(nullValue()));
  }

  @Test
  @DisplayName("未预料异常 → 500，响应体固定话术 Internal server error")
  void unexpectedException_mapsTo500WithFixedMessage() throws Exception {
    mockMvc
        .perform(get("/probe/illegal-state"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(jsonPath("$.data").value(nullValue()))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  @DisplayName("500 响应体不泄漏内部异常描述（连接串、堆栈一类）")
  void internalError_doesNotLeakExceptionMessage() throws Exception {
    MvcResult result =
        mockMvc
            .perform(get("/probe/illegal-state"))
            .andExpect(status().isInternalServerError())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(body)
        .as("内部异常 message 只进日志，MUST NOT 出现在响应体里")
        .doesNotContain("jdbc:sqlite")
        .doesNotContain("connect failed");
  }
}
