package io.oryxlabs.oryxos.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentService} 的验收测试（课件第 17 节「四、验收 harness」）。
 *
 * <p>守护的三条：处理期间「当前是哪个 Agent」对工具执行可见（FR-009）；处理结束——<b>哪怕中途抛异常</b>——一定被清掉
 * （SC-007）；处理结束后会话经持久化接缝被保存一次。
 *
 * <p>全程替身，不碰网络。
 */
class AgentServiceTest {

  private static final String SESSION_ID = "cli:alice:demo";

  private ProfileRegistry profileRegistry;
  private ReActLoop reActLoop;
  private SessionService sessionService;
  private AgentService agentService;
  private Profile profile;
  private Session session;

  @BeforeEach
  void setUp() {
    profileRegistry = mock(ProfileRegistry.class);
    reActLoop = mock(ReActLoop.class);
    sessionService = mock(SessionService.class);
    agentService = new AgentService(profileRegistry, reActLoop, sessionService);
    profile = profileOf("demo");
    session = Session.create(SESSION_ID, "cli", "alice", "demo");
    when(profileRegistry.find("demo")).thenReturn(Optional.of(profile));
  }

  @AfterEach
  void tearDown() {
    // 用例间互相污染会让「清掉了」这类断言变成假绿
    ProfileContext.clear();
  }

  @Test
  @DisplayName("处理期间能读到当前 Agent，且等于本次会话所属的那个")
  void exposesCurrentProfileDuringProcessing() {
    AtomicReference<Profile> seenDuringProcessing = new AtomicReference<>();
    when(reActLoop.run(any(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              seenDuringProcessing.set(ProfileContext.current());
              return "查完了。";
            });

    agentService.process(session, "今天天气怎么样？");

    assertThat(seenDuringProcessing.get()).isSameAs(profile);
  }

  @Test
  @DisplayName("正常结束：当前 Agent 已被清掉")
  void clearsProfileContextOnNormalExit() {
    when(reActLoop.run(any(), anyString(), any())).thenReturn("查完了。");

    agentService.process(session, "今天天气怎么样？");

    assertNull(ProfileContext.current());
  }

  @Test
  @DisplayName("处理中抛异常：ProfileContext 也必须被清掉")
  void clearsProfileContextEvenWhenProcessingThrows() {
    when(reActLoop.run(any(), anyString(), any()))
        .thenThrow(new IllegalStateException("Provider 侧故障"));

    assertThrows(IllegalStateException.class, () -> agentService.process(session, "今天天气怎么样？"));

    assertNull(ProfileContext.current());
  }

  @Test
  @DisplayName("处理结束：会话经持久化接缝被保存一次")
  void savesSessionThroughSeam() {
    when(reActLoop.run(any(), anyString(), any())).thenReturn("查完了。");

    agentService.process(session, "今天天气怎么样？");

    verify(sessionService).save(session);
  }

  @Test
  @DisplayName("无状态调用：两次调用各自拿到一个全新的空会话，互相看不见（第 26 节 FR-002 / 裁决 ⑫）")
  void processStateless_isolatesEveryCall() {
    List<Session> seen = new ArrayList<>();
    when(reActLoop.run(any(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              seen.add(invocation.getArgument(0, Session.class));
              return "第一次答复";
            });

    agentService.processStateless("demo", "帮我算一下 3 的 7 次方");
    agentService.processStateless("demo", "再算一次");

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).id()).isNotEqualTo(seen.get(1).id());
    assertThat(seen).allSatisfy(session -> assertThat(session.messages()).isEmpty());
  }

  @Test
  @DisplayName("无状态调用不落库：不调用 sessionService.save（FR-002）")
  void processStateless_neverPersistsSession() {
    when(reActLoop.run(any(), anyString(), any())).thenReturn("算好了");

    agentService.processStateless("demo", "帮我算一下 3 的 7 次方");

    verify(sessionService, never()).save(any());
  }

  @Test
  @DisplayName("无状态调用同样清上下文：正常与异常路径都不留 ProfileContext")
  void processStateless_clearsProfileContext() {
    when(reActLoop.run(any(), anyString(), any())).thenReturn("算好了");
    agentService.processStateless("demo", "帮我算一下");
    assertNull(ProfileContext.current());

    when(reActLoop.run(any(), anyString(), any()))
        .thenThrow(new IllegalStateException("Provider 侧故障"));
    assertThrows(IllegalStateException.class, () -> agentService.processStateless("demo", "再来一次"));
    assertNull(ProfileContext.current());
  }

  @Test
  @DisplayName("Profile 查不到：抛 IllegalStateException，消息含 Agent 名")
  void unknownProfileThrowsNamingAgent() {
    Session orphan = Session.create("cli:bob:ghost", "cli", "bob", "ghost");

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> agentService.process(orphan, "你好"));

    assertThat(thrown.getMessage()).contains("ghost");
    assertNull(ProfileContext.current());
  }

  private static Profile profileOf(String name) {
    return new Profile(
        name,
        null,
        new Profile.Identity(name, "你是演示助手。"),
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }
}
