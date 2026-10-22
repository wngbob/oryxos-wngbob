package io.oryxlabs.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionMessage;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.core.tool.ToolExecutionContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 固定管理台会话与执行上下文（第 30 节 FR-008，contracts/workspace-memory.md §4）。
 *
 * <p><b>为什么固定会话值得单测</b>：它是「同一个 Agent 多轮聊下去」的唯一载体——三元组里任一写错（比如把 {@code console}
 * 写成当前登录名），每次打开详情页都是一条新会话，用户会以为「历史丢了」；而这条 bug 在功能上完全看不出来，因为每一轮都能正常答复。
 *
 * <p><b>记忆文件路径不在这里断</b>：{@code LongTermMemoryStore} / {@code MarkdownMemoryStore} 在 {@code
 * oryxos-memory} 模块，{@code oryxos-core} MUST NOT 依赖它（依赖方向硬约束）。per-agent 记忆的落盘判卷在 {@code
 * PerAgentMemoryScopeTest}； 本类只判 {@link ToolExecutionContext} 的置入 / 复原语义。
 */
class AgentFixedSessionTest {

  private static final String AGENT = "weather";

  private static final String FIXED_ID = "admin:console:" + AGENT;

  private final ProfileRegistry registry = mock(ProfileRegistry.class);
  private final AgentScheduler scheduler = mock(AgentScheduler.class);
  private final AgentStore store = mock(AgentStore.class);
  private final LlmClient llmClient = mock(LlmClient.class);
  private final ToolCatalog toolCatalog = mock(ToolCatalog.class);
  private final AgentService agentService = mock(AgentService.class);

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  @Test
  @DisplayName("固定会话的标识恒为 admin:console:<name>，两次取回是同一条")
  void fixedSessionIdIsStable() {
    AgentLifecycleService service = serviceWith(new InMemorySessionService());

    Session first = service.fixedSession(AGENT);
    Session second = service.fixedSession(AGENT);

    assertThat(first.id()).isEqualTo(FIXED_ID);
    assertThat(second.id()).isEqualTo(FIXED_ID);
    assertThat(first.channel()).isEqualTo("admin");
    assertThat(first.userId()).isEqualTo("console");
    assertThat(first.profileName()).isEqualTo(AGENT);
    assertThat(second).isSameAs(first);
  }

  @Test
  @DisplayName("两轮消息落在同一条会话里（上下文可累积，这正是它区别于 invoke 的地方）")
  void twoMessagesLandInTheSameSession() {
    InMemorySessionService sessions = new InMemorySessionService();
    doAnswer(
            invocation -> {
              Session session = invocation.getArgument(0);
              String content = invocation.getArgument(1);
              session.appendUserMessage(content);
              return "收到：" + content;
            })
        .when(agentService)
        .process(any(Session.class), any(String.class));
    AgentLifecycleService service = serviceWith(sessions);

    assertThat(service.sendToFixedSession(AGENT, "第一句")).isEqualTo("收到：第一句");
    assertThat(service.sendToFixedSession(AGENT, "第二句")).isEqualTo("收到：第二句");

    Session session = sessions.get(FIXED_ID).orElseThrow();
    assertThat(session.messages())
        .extracting(SessionMessage::content)
        .containsExactly("第一句", "第二句");
    assertThat(sessions.getOrCreateCalls).isEqualTo(2);
  }

  @Test
  @DisplayName("往已归档的固定会话发消息被拒，且不进入 ReAct（一个模型调用都不发）")
  void sendingToArchivedSessionIsRejected() {
    Session archived = mock(Session.class);
    when(archived.status()).thenReturn(Session.STATUS_ARCHIVED);
    when(archived.id()).thenReturn(FIXED_ID);
    SessionService sessionService = mock(SessionService.class);
    when(sessionService.getOrCreate("admin", "console", AGENT)).thenReturn(archived);
    AgentLifecycleService service = serviceWith(sessionService);

    assertThatThrownBy(() -> service.sendToFixedSession(AGENT, "还有人吗"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("会话已归档");

    verifyNoInteractions(agentService, llmClient);
  }

  @Test
  @DisplayName("callAs 结束后复原旧值，异常路径同样复原（泄漏的上下文会让下一个调用读到上一个 Agent）")
  void callAsRestoresPreviousValue() {
    ToolExecutionContext.set("outer");

    assertThat(ToolExecutionContext.callAs("inner", ToolExecutionContext::current))
        .isEqualTo("inner");
    assertThat(ToolExecutionContext.current()).isEqualTo("outer");

    assertThatThrownBy(
            () ->
                ToolExecutionContext.callAs(
                    "boom",
                    () -> {
                      throw new IllegalStateException("工具炸了");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ToolExecutionContext.current()).isEqualTo("outer");

    ToolExecutionContext.clear();
    assertThat(ToolExecutionContext.callAs("solo", ToolExecutionContext::current))
        .isEqualTo("solo");
    assertThat(ToolExecutionContext.current()).isNull();
  }

  private AgentLifecycleService serviceWith(SessionService sessionService) {
    when(registry.find(AGENT)).thenReturn(Optional.of(profile()));
    return new AgentLifecycleService(
        mock(ProfileLoader.class),
        registry,
        scheduler,
        store,
        llmClient,
        toolCatalog,
        sessionService,
        agentService,
        List.of("deepseek", "mock"),
        "deepseek",
        "deepseek-chat");
  }

  private static Profile profile() {
    return new Profile(
        AGENT,
        "每天查天气",
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec("mock", "mock-model", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  /** 只用一张内存表实现六个操作：本类判的是「服务怎么用它」，不是持久化本身。 */
  private static final class InMemorySessionService implements SessionService {

    private final Map<String, Session> sessions = new LinkedHashMap<>();

    private int getOrCreateCalls;

    @Override
    public Session getOrCreate(String channel, String userId, String profileName) {
      getOrCreateCalls++;
      String id = channel + ":" + userId + ":" + profileName;
      return sessions.computeIfAbsent(id, key -> Session.create(key, channel, userId, profileName));
    }

    @Override
    public Optional<Session> get(String sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void save(Session session) {
      sessions.put(session.id(), session);
    }

    @Override
    public boolean archive(String sessionId) {
      return sessions.containsKey(sessionId);
    }

    @Override
    public long countActive() {
      return sessions.size();
    }

    @Override
    public List<Session> listRecent(int limit) {
      return sessions.values().stream().limit(Math.max(limit, 0)).toList();
    }
  }
}
