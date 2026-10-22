package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.core.context.ContextLoader;
import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.engine.PromptBuilder;
import io.oryxlabs.oryxos.core.engine.ReActLoop;
import io.oryxlabs.oryxos.core.engine.ToolExecutor;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.JpaSessionService;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionMessage;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.ToolExecutionContext;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.MemoryServiceImpl;
import io.oryxlabs.oryxos.memory.builtin.MemoryTools;
import io.oryxlabs.oryxos.memory.store.MarkdownMemoryStore;
import io.oryxlabs.oryxos.provider.LlmCallRecorder;
import io.oryxlabs.oryxos.provider.MockChatModel;
import io.oryxlabs.oryxos.provider.ProviderService;
import io.oryxlabs.oryxos.provider.ToolSchemaAdapter;
import io.oryxlabs.oryxos.storage.entity.SessionEntity;
import io.oryxlabs.oryxos.storage.entity.ToolInvocation;
import io.oryxlabs.oryxos.storage.repository.SessionRepository;
import io.oryxlabs.oryxos.storage.repository.ToolInvocationRepository;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.web.controller.SessionApiController;
import io.oryxlabs.oryxos.web.controller.ToolApiController;
import io.oryxlabs.oryxos.web.controller.dto.ToolView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Sort;

/**
 * 无 key 全链路（第 27 节「验收 harness」；手工装配，快）。
 *
 * <p><b>只有「模型」是假的</b>：{@link MockChatModel} 顶掉真厂商，{@code ReActLoop} / {@code PromptBuilder} /
 * {@code ToolExecutor} / {@code MemoryService} / {@code SessionService} / 审计全部是真的。所以它判的是<b>接线</b>：
 * 一次对话能不能走完「想 → 查 → 答 → 记账」，而不是「mock 会不会写字符串」。
 *
 * <p>三根支柱在这里各取一条最强断言：<b>答得出</b>（答复是脚本的最终答复、历史四条按序齐备）、<b>记忆写得出</b> （{@code MEMORY.md}
 * 里真有那句话）、<b>工具查得到</b>（注册表里有 {@code save_memory}）。再加一条对账：{@code llm_calls} 恰 2 条、{@code
 * tool_invocations} 恰 1 条——<b>多一条少一条都算失败</b>。
 *
 * <p><b>为什么不启 Spring</b>：整机那层由 {@code MockAgentE2ETest} 判（真端口 + 真 SQLite）。本类把装配成本压到 {@code new}
 * 几次，红了先跑它——能在秒级分清「链路断了」还是「容器没起来」。
 */
class MockProviderFlowTest {

  private static final String AGENT = "mock-agent";

  private static final String FACT = "记住：我喜欢喝美式咖啡";

  @TempDir Path tempDir;

  private Workspace workspace;

  private ProfileRegistry profileRegistry;

  private SessionService sessionService;

  private MemoryService memoryService;

  private AgentService agentService;

  private ToolRegistry toolRegistry;

  private final List<RecordedLlmCall> llmCalls = new ArrayList<>();

  private final List<ToolInvocation> toolInvocations = new ArrayList<>();

  private final Map<String, SessionEntity> sessionRows = new LinkedHashMap<>();

  @BeforeEach
  void assembleByHand() throws IOException {
    workspace = new Workspace(tempDir);
    Files.createDirectories(workspace.root().resolve("memory"));
    writeAgent();

    profileRegistry =
        new ProfileRegistry(
            new ProfileLoader(
                    workspace.agentsDir(), Set.of("deepseek", MockChatModel.PROVIDER_NAME))
                .loadAll());

    sessionService = new JpaSessionService(sessionRepository());
    memoryService = new MemoryServiceImpl(new MarkdownMemoryStore(workspace.root()));
    toolRegistry = new ToolRegistry();
    toolRegistry.registerAnnotated(new MemoryTools(memoryService), "builtin");

    LlmClient provider =
        new ProviderService(
            Map.of(MockChatModel.PROVIDER_NAME, new MockChatModel()),
            new ToolSchemaAdapter(),
            recordingRecorder());
    ReActLoop loop =
        new ReActLoop(
            new PromptBuilder(new ContextLoader(workspace.root()), memoryService, toolRegistry),
            provider,
            new ToolExecutor(toolRegistry, invocationRepository()));
    agentService = new AgentService(profileRegistry, loop, sessionService);
  }

  @Test
  @DisplayName("一次「记住…」的对话：ReAct 两轮、save_memory 恰一次、MEMORY.md 记下事实、历史四条齐备")
  void rememberMessageDrivesWholeChain() throws IOException {
    Session session = sessionService.getOrCreate("web", "alice", AGENT);

    String reply = agentService.process(session, FACT);

    assertThat(reply).isEqualTo(MockChatModel.FINAL_REPLY);
    // 两轮：第一轮模型要工具、第二轮拿工具结果给答复——不多不少（validate 的是「账记得对」）
    assertThat(llmCalls).hasSize(2);
    assertThat(llmCalls).allMatch(RecordedLlmCall::success);

    assertThat(toolInvocations).hasSize(1);
    ToolInvocation invocation = toolInvocations.get(0);
    assertThat(invocation.getToolName()).isEqualTo(MockChatModel.SCRIPTED_TOOL);
    assertThat(invocation.getSuccess()).isTrue();
    assertThat(invocation.getSessionId()).isEqualTo(session.id());

    // 长期记忆真的落了文件（默认 markdown 档），不是只写了个内存变量。
    // 第 30 节起 Markdown 档按 Agent 隔离，落点是 agents/<name>/MEMORY.md——工具执行时上下文由 ToolExecutor 置入
    String memoryFile =
        Files.readString(
            workspace.agentsDir().resolve(AGENT).resolve("MEMORY.md"), StandardCharsets.UTF_8);
    assertThat(memoryFile).contains("我喜欢喝美式咖啡");

    // 会话历史：user → assistant(工具请求) → tool(结果) → assistant(最终答复)
    Session reloaded = sessionService.get(session.id()).orElseThrow();
    assertThat(reloaded.messages())
        .extracting(SessionMessage::role)
        .containsExactly(
            SessionMessage.Role.USER,
            SessionMessage.Role.ASSISTANT,
            SessionMessage.Role.TOOL,
            SessionMessage.Role.ASSISTANT);
    assertThat(reloaded.messages().get(0).content()).isEqualTo(FACT);
    assertThat(reloaded.messages().get(3).content()).isEqualTo(MockChatModel.FINAL_REPLY);
  }

  @Test
  @DisplayName("同一批服务起三个查询端点：会话 / 记忆 / 工具都查得到刚才那条链路留下的数据")
  void queryEndpointsSeeTheSameData() {
    Session session = sessionService.getOrCreate("web", "alice", AGENT);
    agentService.process(session, FACT);

    var sessionsApi = new SessionApiController(sessionService, agentService, profileRegistry);
    var listed = sessionsApi.list(null).data();
    assertThat(listed).hasSize(1);
    assertThat(listed.get(0).sessionId()).isEqualTo(session.id());
    assertThat(listed.get(0).messageCount()).isEqualTo(4);
    assertThat(sessionsApi.get(session.id()).data().messages()).hasSize(4);

    // 记忆自第 30 节起按 Agent 隔离：读法与 `GET /api/v1/agents/{name}/memory` 相同——分区读取也置入 Agent 上下文。
    // 全局 GET /api/v1/memory 本节移除，故不再经 MemoryApiController（无上下文读的是全局路径，那里此刻本来就是空的）
    var memory = ToolExecutionContext.callAs(AGENT, memoryService::partition);
    assertThat(memory.core() + memory.archival()).contains("我喜欢喝美式咖啡");

    var tools = new ToolApiController(toolRegistry).list().data();
    assertThat(tools).extracting(ToolView::name).contains("save_memory", "recall_memory");
  }

  private void writeAgent() throws IOException {
    Path dir = Files.createDirectories(workspace.agentsDir().resolve(AGENT));
    Files.writeString(
        dir.resolve("AGENT.md"),
        """
        ---
        description: 无 key 全链路自测 Agent
        identity:
          agent_name: 小助手
          prompt: 你是 OryxOS 的全链路自测助手。
        provider:
          name: mock
          model: mock-model
        tools:
          - save_memory
        ---

        按脚本把用户说的话记进长期记忆。
        """,
        StandardCharsets.UTF_8);
  }

  /** 会话表替身：`JpaSessionService` 是真的，只有「存到哪」换成一张内存表。 */
  private SessionRepository sessionRepository() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.findById(anyString()))
        .thenAnswer(
            call -> Optional.ofNullable(sessionRows.get(call.getArgument(0, String.class))));
    when(repository.save(any(SessionEntity.class)))
        .thenAnswer(
            call -> {
              SessionEntity entity = call.getArgument(0, SessionEntity.class);
              sessionRows.put(entity.getSessionId(), entity);
              return entity;
            });
    when(repository.findAll(any(Sort.class)))
        .thenAnswer(
            call ->
                sessionRows.values().stream()
                    .sorted(Comparator.comparing(SessionEntity::getLastActiveAt).reversed())
                    .toList());
    return repository;
  }

  private ToolInvocationRepository invocationRepository() {
    ToolInvocationRepository repository = mock(ToolInvocationRepository.class);
    when(repository.save(any(ToolInvocation.class)))
        .thenAnswer(
            call -> {
              ToolInvocation invocation = call.getArgument(0, ToolInvocation.class);
              toolInvocations.add(invocation);
              return invocation;
            });
    return repository;
  }

  private LlmCallRecorder recordingRecorder() {
    return (sessionId, provider, model, usage, success, errorMessage, durationMs) ->
        llmCalls.add(new RecordedLlmCall(provider, model, success));
  }

  private record RecordedLlmCall(String provider, String model, boolean success) {}
}
