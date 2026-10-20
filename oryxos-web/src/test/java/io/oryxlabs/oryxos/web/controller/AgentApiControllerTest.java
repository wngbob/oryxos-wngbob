package io.oryxlabs.oryxos.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 无状态调用端点的验收测试（contracts/rest-endpoints.md §2）。
 *
 * <p>守点：正常调用返回 {@code {agent, reply}}；Agent 不存在 → 404（不是 503）；参数错误（空内容、超 32KB）→ 400 且
 * <b>零次</b>处理调用（Controller 自己把非法请求挡在门外）。
 */
@WebMvcTest(controllers = AgentApiController.class)
class AgentApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AgentService agentService;

  @MockitoBean private ProfileRegistry profileRegistry;

  @Test
  @DisplayName("正常调用：返回 {agent, reply}，且走的是无状态入口")
  void invoke_returnsAgentAndReply() throws Exception {
    when(profileRegistry.find("daily-weather")).thenReturn(Optional.of(mock(Profile.class)));
    when(agentService.processStateless("daily-weather", "帮我算一下 3 的 7 次方")).thenReturn("2187");

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/invoke", "daily-weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "帮我算一下 3 的 7 次方"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.agent").value("daily-weather"))
        .andExpect(jsonPath("$.data.reply").value("2187"));

    verify(agentService).processStateless("daily-weather", "帮我算一下 3 的 7 次方");
  }

  @Test
  @DisplayName("Agent 不存在 → 404，话术逐字 agent not found: <name>")
  void invoke_unknownAgent_returns404() throws Exception {
    when(profileRegistry.find("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/invoke", "ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "你好"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("agent not found: ghost"));

    verify(agentService, never()).processStateless(eq("ghost"), eq("你好"));
  }

  @Test
  @DisplayName("内容为空 → 400，且零次处理调用")
  void invoke_blankContent_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agents/{name}/invoke", "daily-weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "   "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.data").value(nullValue()));

    verify(agentService, never()).processStateless(eq("daily-weather"), eq("   "));
  }

  @Test
  @DisplayName("超 32KB → 400，话术逐字 message exceeds 32KB limit")
  void invoke_contentOver32KB_returns400() throws Exception {
    String tooLong = "x".repeat(32 * 1024 + 1);

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/invoke", "daily-weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", tooLong))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("message exceeds 32KB limit"));

    verify(agentService, never()).processStateless(eq("daily-weather"), eq(tooLong));
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }
}
