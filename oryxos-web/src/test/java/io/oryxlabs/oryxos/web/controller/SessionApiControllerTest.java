package io.oryxlabs.oryxos.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.time.Instant;
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
 * 会话四端点的验收测试（课件第 26 节「验收 harness」/ contracts/rest-endpoints.md §1）。
 *
 * <p>守点：超 32KB → 400 且<b>零次</b>模型调用；会话不存在 → 404；正常请求下 {@code agentService.process} <b>恰好一次</b>
 * （Controller 没夹带私货，FR-002）；归档会话再发消息 → 400 且不复活；历史最多最近 100 条、按时间正序；归档幂等。
 *
 * <p>全程 {@code @WebMvcTest} 切片 + mock，不碰数据库、不碰网络。
 */
@WebMvcTest(controllers = SessionApiController.class)
class SessionApiControllerTest {

  private static final String SESSION_ID = "web:alice:daily-weather";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SessionService sessionService;

  @MockitoBean private AgentService agentService;

  @MockitoBean private ProfileRegistry profileRegistry;

  @Test
  @DisplayName("正常请求：处理入口恰被调用一次（Controller 没夹带私货，FR-002）")
  void normalMessage_callsProcessExactlyOnce() throws Exception {
    givenSession(activeSession());
    when(profileRegistry.find("daily-weather")).thenReturn(Optional.of(mock(Profile.class)));
    when(agentService.process(any(Session.class), eq("北京今天天气怎么样"))).thenReturn("晴，26 度。");

    mockMvc
        .perform(
            post("/api/v1/sessions/{id}/messages", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "北京今天天气怎么样"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
        .andExpect(jsonPath("$.data.reply").value("晴，26 度。"));

    verify(agentService, times(1)).process(any(Session.class), eq("北京今天天气怎么样"));
  }

  @Test
  @DisplayName("超 32KB → 400，且一次模型调用都不发生（FR-008 / FR-022）")
  void contentOver32KB_returns400AndNeverCallsProcess() throws Exception {
    givenSession(activeSession());
    String tooLong = "x".repeat(32 * 1024 + 1);

    mockMvc
        .perform(
            post("/api/v1/sessions/{id}/messages", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", tooLong))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("message exceeds 32KB limit"))
        .andExpect(jsonPath("$.data").value(nullValue()));

    verify(agentService, never()).process(any(Session.class), any(String.class));
  }

  @Test
  @DisplayName("会话不存在 → 404，话术逐字 session not found: <id>")
  void sessionNotFound_returns404() throws Exception {
    when(sessionService.get(SESSION_ID)).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/sessions/{id}/messages", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "在吗"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("session not found: " + SESSION_ID));
  }

  @Test
  @DisplayName("归档会话再发消息 → 400，且不静默复活（零模型调用）")
  void archivedSession_returns400WithoutCallingProcess() throws Exception {
    // 归档态经 SessionService 读回，测试替身直接给出「读回来就是归档态」的会话；
    // Session 是 final 类，Mockito 5 的 inline mock maker 可以替身它，只桩住被判的那一个字段
    Session archived = mock(Session.class);
    when(archived.status()).thenReturn(Session.STATUS_ARCHIVED);
    givenSession(archived);

    mockMvc
        .perform(
            post("/api/v1/sessions/{id}/messages", SESSION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "再来一句"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("session is archived: " + SESSION_ID));

    verify(agentService, never()).process(any(Session.class), any(String.class));
  }

  @Test
  @DisplayName("查历史：最多最近 100 条，按时间正序（FR-009）")
  void history_returnsAtMost100NewestInOrder() throws Exception {
    Session session = activeSession();
    for (int i = 1; i <= 120; i++) {
      session.appendUserMessage("m" + i);
    }
    givenSession(session);

    mockMvc
        .perform(get("/api/v1/sessions/{id}", SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages.length()").value(100))
        .andExpect(jsonPath("$.data.messages[0].content").value("m21"))
        .andExpect(jsonPath("$.data.messages[99].content").value("m120"))
        .andExpect(jsonPath("$.data.messages[0].role").value("user"));
  }

  @Test
  @DisplayName("归档幂等：两次 DELETE 都 200，archivedAt 相同（FR-021）")
  void deleteTwice_returnsSameArchivedAt() throws Exception {
    Instant archivedAt = Instant.parse("2026-10-19T10:00:00Z");
    Session archived = mock(Session.class);
    when(archived.status()).thenReturn(Session.STATUS_ARCHIVED);
    when(archived.archivedAt()).thenReturn(archivedAt);
    givenSession(archived);
    when(sessionService.archive(SESSION_ID)).thenReturn(true);

    mockMvc
        .perform(delete("/api/v1/sessions/{id}", SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
        .andExpect(jsonPath("$.data.status").value("archived"))
        .andExpect(jsonPath("$.data.archivedAt").exists());

    mockMvc
        .perform(delete("/api/v1/sessions/{id}", SESSION_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archivedAt").value("2026-10-19T10:00:00Z"));
  }

  @Test
  @DisplayName("创建会话时 Agent 不存在 → 404，话术逐字 agent not found: <name>（FR-007）")
  void create_unknownProfile_returns404() throws Exception {
    when(profileRegistry.find("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("profile", "ghost", "userId", "alice"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("agent not found: ghost"));
  }

  @Test
  @DisplayName("创建会话：channel 服务端定死 web，userId 缺省 default")
  void create_defaultsUserIdAndFixesChannel() throws Exception {
    Session created =
        Session.create("web:default:daily-weather", "web", "default", "daily-weather");
    when(profileRegistry.find("daily-weather")).thenReturn(Optional.of(mock(Profile.class)));
    when(sessionService.getOrCreate("web", "default", "daily-weather")).thenReturn(created);

    mockMvc
        .perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("profile", "daily-weather"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("web:default:daily-weather"))
        .andExpect(jsonPath("$.data.channel").value("web"))
        .andExpect(jsonPath("$.data.userId").value("default"))
        .andExpect(jsonPath("$.data.status").value("active"));

    // 创建必须当场落库（2026-10-19 实测缺陷的回归判据）：getOrCreate 对新三元组只造对象、不写行，
    // 少了这一步，「先建会话、再发消息」会在第二步 get(id) 处 404
    verify(sessionService).save(created);
  }

  private void givenSession(Session session) {
    when(sessionService.get(SESSION_ID)).thenReturn(Optional.of(session));
  }

  private Session activeSession() {
    return Session.create(SESSION_ID, "web", "alice", "daily-weather");
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }
}
