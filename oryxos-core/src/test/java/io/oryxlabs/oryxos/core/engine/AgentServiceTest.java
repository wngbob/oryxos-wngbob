package io.oryxlabs.oryxos.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
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
    session = Session.create(SESSION_ID, "demo");
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
  @DisplayName("Profile 查不到：抛 IllegalStateException，消息含 Agent 名")
  void unknownProfileThrowsNamingAgent() {
    Session orphan = Session.create("cli:bob:ghost", "ghost");

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
