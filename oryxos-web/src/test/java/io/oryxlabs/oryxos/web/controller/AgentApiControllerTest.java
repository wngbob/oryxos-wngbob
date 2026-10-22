package io.oryxlabs.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.core.agent.AgentAuthorUnavailableException;
import io.oryxlabs.oryxos.core.agent.AgentLifecycleService;
import io.oryxlabs.oryxos.core.agent.AgentView;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.tool.ToolExecutionContext;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Agent 资源端点的验收测试（第 26 节 {@code invoke} + 第 30 节生命周期十一条，contracts/agent-lifecycle-api.md §2）。
 *
 * <p><b>守点一：控制器是薄转发</b>——每条端点都 {@code verify} 到了编排层那一个方法。控制器里多写一行判断， 就等于给「API 建的 Agent」与「丢目录进来的
 * Agent」造出两套行为，而那两套行为在真实部署里没人对得上。
 *
 * <p><b>守点二：错误语义</b>——不存在 → 404（不是 400/500）；编排层说参数错 / 冲突 → 400；作者模型没配 → 503；
 * 被拒的请求<b>零模型调用</b>（{@code verifyNoInteractions}）。
 *
 * <p><b>守点三：Agent 记忆端点自己置入执行上下文</b>（FR-007）。控制器要 {@code callAs(name)} 再读门面： 不置入的话每个 Agent
 * 读到的都是全局那份记忆，而页面上看起来完全正常——故用 {@code thenAnswer} 把 「门面被调用那一刻 {@link ToolExecutionContext#current()}
 * 是什么」抓下来判。
 */
@WebMvcTest(controllers = AgentApiController.class)
class AgentApiControllerTest {

  private static final String AGENT = "daily-weather";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private AgentService agentService;

  @MockitoBean private ProfileRegistry profileRegistry;

  @MockitoBean private AgentLifecycleService lifecycleService;

  /** 第 30 节：{@code GET /{name}/memory} 的数据源（门面的分区视图）。 */
  @MockitoBean private MemoryService memoryService;

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  // ---------- 既有：一次性调用 ----------

  @Test
  @DisplayName("正常调用：返回 {agent, reply}，且走的是无状态入口")
  void invoke_returnsAgentAndReply() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(agentService.processStateless(AGENT, "帮我算一下 3 的 7 次方")).thenReturn("2187");

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/invoke", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "帮我算一下 3 的 7 次方"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.agent").value(AGENT))
        .andExpect(jsonPath("$.data.reply").value("2187"));

    verify(agentService).processStateless(AGENT, "帮我算一下 3 的 7 次方");
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
            post("/api/v1/agents/{name}/invoke", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "   "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.data").value(nullValue()));

    verify(agentService, never()).processStateless(eq(AGENT), eq("   "));
  }

  @Test
  @DisplayName("超 32KB → 400，话术逐字 message exceeds 32KB limit")
  void invoke_contentOver32KB_returns400() throws Exception {
    String tooLong = "x".repeat(32 * 1024 + 1);

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/invoke", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", tooLong))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("message exceeds 32KB limit"));

    verify(agentService, never()).processStateless(eq(AGENT), eq(tooLong));
  }

  // ---------- 生命周期：增删改查 ----------

  @Test
  @DisplayName("新建：转发给编排层，返回统一信封四字段")
  void create_delegatesToLifecycle() throws Exception {
    when(lifecycleService.create(AGENT, "每天早上九点查北京天气")).thenReturn(view(AGENT));

    mockMvc
        .perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", AGENT, "description", "每天早上九点查北京天气"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.data.name").value(AGENT))
        .andExpect(jsonPath("$.timestamp").exists());

    verify(lifecycleService).create(AGENT, "每天早上九点查北京天气");
  }

  @Test
  @DisplayName("新建时名称冲突 → 400（编排层的话术原样透出），一次脚手架都不做")
  void create_conflict_returns400() throws Exception {
    when(lifecycleService.create(AGENT, null))
        .thenThrow(new IllegalArgumentException("Agent 已存在: '" + AGENT + "'"));

    mockMvc
        .perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", AGENT))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("Agent 已存在: '" + AGENT + "'"));
  }

  @Test
  @DisplayName("新建时名称为空 → 400，且零次编排调用")
  void create_blankName_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "  "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("name is required"));

    verifyNoInteractions(lifecycleService);
  }

  @Test
  @DisplayName("列表：直接转发编排层的按名排序结果")
  void list_delegates() throws Exception {
    when(lifecycleService.list()).thenReturn(List.of(view("a-agent"), view("b-agent")));

    mockMvc
        .perform(get("/api/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("a-agent"))
        .andExpect(jsonPath("$.data[1].name").value("b-agent"));
  }

  @Test
  @DisplayName("详情：不存在 → 404，且不碰编排层")
  void get_unknownAgent_returns404() throws Exception {
    when(profileRegistry.find("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/agents/{name}", "ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("agent not found: ghost"));

    verifyNoInteractions(lifecycleService);
  }

  @Test
  @DisplayName("改 AGENT.md：整份正文转发给 update，返回新视图")
  void update_delegates() throws Exception {
    String markdown = "---\nprovider:\n  name: mock\n  model: mock-model\n---\n正文";
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(lifecycleService.update(AGENT, markdown)).thenReturn(view(AGENT));

    mockMvc
        .perform(
            put("/api/v1/agents/{name}", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("agentMarkdown", markdown))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(AGENT));

    verify(lifecycleService).update(AGENT, markdown);
  }

  @Test
  @DisplayName("改 AGENT.md：正文为空 → 400，且零次编排调用")
  void update_blankMarkdown_returns400() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));

    mockMvc
        .perform(
            put("/api/v1/agents/{name}", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("agentMarkdown", " "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("agentMarkdown is required"));

    verifyNoInteractions(lifecycleService);
  }

  @Test
  @DisplayName("删除：转发给编排层（注销定时 → 移出索引 → 归档），data 为 null")
  void delete_delegates() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));

    mockMvc
        .perform(delete("/api/v1/agents/{name}", AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data").value(nullValue()));

    verify(lifecycleService).delete(AGENT);
  }

  // ---------- 一句话生成 / 保存一组文件 ----------

  @Test
  @DisplayName("生成：转发描述，原样返回「相对路径 → 内容」草稿")
  void generateFiles_delegatesAndReturnsDraft() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(lifecycleService.generateFiles(AGENT, "每天早上九点查北京天气"))
        .thenReturn(Map.of("AGENT.md", "---\nname: daily-weather\n---"));

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/generate-files", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("description", "每天早上九点查北京天气"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data['AGENT.md']").exists());
  }

  @Test
  @DisplayName("生成时作者模型没配 → 503（不是 500、也不是空模型打端点）")
  void generateFiles_authorUnavailable_returns503() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(lifecycleService.generateFiles(AGENT, "随便写点"))
        .thenThrow(new AgentAuthorUnavailableException("未配置作者模型：请设置 oryxos.author.model"));

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/generate-files", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("description", "随便写点"))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503))
        .andExpect(jsonPath("$.message").value("未配置作者模型：请设置 oryxos.author.model"));
  }

  @Test
  @DisplayName("保存一组文件：转发文件清单，返回更新后的视图")
  void saveFiles_delegates() throws Exception {
    Map<String, String> files = Map.of("AGENT.md", "---\nname: daily-weather\n---");
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(lifecycleService.saveFiles(AGENT, files)).thenReturn(view(AGENT));

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/files", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("files", files))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value(AGENT));

    verify(lifecycleService).saveFiles(AGENT, files);
  }

  @Test
  @DisplayName("保存一组文件：清单为空 → 400，且零次编排调用")
  void saveFiles_emptyFiles_returns400() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/files", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("files", Map.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("files is required"));

    verifyNoInteractions(lifecycleService);
  }

  // ---------- 专属记忆 / 固定会话 ----------

  @Test
  @DisplayName("记忆：读门面时执行上下文必须是这个 Agent（否则读到的是全局那份）")
  void memory_readsPartitionUnderAgentContext() throws Exception {
    AtomicReference<String> seenAgent = new AtomicReference<>("(未置入)");
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(memoryService.partition())
        .thenAnswer(
            invocation -> {
              seenAgent.set(ToolExecutionContext.current());
              return new MemoryPartition("核心一行", "归档一行", false);
            });

    mockMvc
        .perform(get("/api/v1/agents/{name}/memory", AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.core").value("核心一行"))
        .andExpect(jsonPath("$.data.archival").value("归档一行"))
        .andExpect(jsonPath("$.data.truncated").value(false));

    assertThat(seenAgent.get()).isEqualTo(AGENT);
    assertThat(ToolExecutionContext.current()).as("请求结束后上下文必须复原").isNull();
  }

  @Test
  @DisplayName("固定会话：只回最近 100 条、按正序（第 26/27 节同一口径）")
  void fixedSession_returnsRecentMessages() throws Exception {
    Session session = Session.create("admin:console:" + AGENT, "admin", "console", AGENT);
    for (int i = 1; i <= 105; i++) {
      session.appendUserMessage("第 " + i + " 条");
    }
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(lifecycleService.fixedSession(AGENT)).thenReturn(session);

    mockMvc
        .perform(get("/api/v1/agents/{name}/session", AGENT))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("admin:console:" + AGENT))
        .andExpect(jsonPath("$.data.profile").value(AGENT))
        .andExpect(jsonPath("$.data.messages.length()").value(100))
        .andExpect(jsonPath("$.data.messages[0].content").value("第 6 条"))
        .andExpect(jsonPath("$.data.messages[99].content").value("第 105 条"));
  }

  @Test
  @DisplayName("固定会话发消息：转发给编排层，返回 sessionId + 答复")
  void sendMessage_delegatesAndReturnsReply() throws Exception {
    Session session = Session.create("admin:console:" + AGENT, "admin", "console", AGENT);
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));
    when(lifecycleService.sendToFixedSession(AGENT, "今天穿什么")).thenReturn("穿件外套");
    when(lifecycleService.fixedSession(AGENT)).thenReturn(session);

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/session/messages", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "今天穿什么"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("admin:console:" + AGENT))
        .andExpect(jsonPath("$.data.reply").value("穿件外套"));

    verify(lifecycleService).sendToFixedSession(AGENT, "今天穿什么");
  }

  @Test
  @DisplayName("固定会话发消息：内容为空 / 超 32KB → 400，且一次 ReAct 都不进")
  void sendMessage_blankOrOversized_returns400() throws Exception {
    when(profileRegistry.find(AGENT)).thenReturn(Optional.of(mock(Profile.class)));

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/session/messages", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "  "))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("content is required"));

    mockMvc
        .perform(
            post("/api/v1/agents/{name}/session/messages", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "x".repeat(32 * 1024 + 1)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("message exceeds 32KB limit"));

    verify(lifecycleService, never()).sendToFixedSession(any(), any());
  }

  private static AgentView view(String name) {
    return new AgentView(name, "每天早上九点查北京天气", "mock", "mock-model", List.of("http_get"), List.of());
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }
}
