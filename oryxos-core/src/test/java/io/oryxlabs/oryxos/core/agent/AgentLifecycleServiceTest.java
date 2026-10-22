package io.oryxlabs.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 课件 harness 第 1 行「{@code AgentLifecycleServiceTest}」的落地（第 30 节）。
 *
 * <p>守的是<b>编排顺序与失败回滚</b>，不是文件系统细节（那是 {@code AgentStore} 自己的测试面）：每一步都用 Mockito 钉死「先干什么、后干什么」，
 * 失败路径钉死「前面写下的东西有没有被收回」。顺序错了在真机上是很难看出来的——例如「先注册再写盘」在空目录上照样成功，只在 watcher 与 API 并发时炸；「先注册新定时再注销旧的」会让旧
 * cron 与新 cron 并存，两条一起跑。
 *
 * <p><b>被拒请求零模型调用</b>（FR-022）：冲突 / 未知厂商 / 未注册工具三条用例都断言 {@code verifyNoInteractions(llmClient)} ——
 * 审计零行由「零调用」传递保证（没有调用就没有 {@code llm_calls} 行）。
 */
class AgentLifecycleServiceTest {

  /** 测试用的工作区根；{@code AgentStore} 被 mock，故这个路径只需要是个绝对路径。 */
  private static final Path WORKSPACE = Path.of("/tmp/oryxos-l30-test/.oryxos");

  /** 脚手架产出的 Agent 目录。 */
  private static final Path AGENT_DIR = WORKSPACE.resolve("agents/weather");

  private final ProfileLoader profileLoader = mock(ProfileLoader.class);
  private final ProfileRegistry registry = mock(ProfileRegistry.class);
  private final AgentScheduler scheduler = mock(AgentScheduler.class);
  private final AgentStore store = mock(AgentStore.class);
  private final LlmClient llmClient = mock(LlmClient.class);
  private final ToolCatalog toolCatalog = mock(ToolCatalog.class);
  private final SessionService sessionService = mock(SessionService.class);
  private final AgentService agentService = mock(AgentService.class);

  private final AgentLifecycleService service =
      new AgentLifecycleService(
          profileLoader,
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

  @Test
  @DisplayName("AgentLifecycleServiceTest：create 按「脚手架 → 派生 → 索引注册 → 注册定时」顺序执行")
  void create_runsStepsInOrder() {
    Profile profile = profile("weather", List.of(schedule("0 9 * * *")));
    when(store.scaffold("weather", "每天查天气", "deepseek", "deepseek-chat")).thenReturn(AGENT_DIR);
    when(profileLoader.deriveProfile(AGENT_DIR)).thenReturn(profile);

    AgentView view = service.create("weather", "每天查天气");

    InOrder order = inOrder(store, profileLoader, registry, scheduler);
    order.verify(store).scaffold("weather", "每天查天气", "deepseek", "deepseek-chat");
    order.verify(profileLoader).deriveProfile(AGENT_DIR);
    order.verify(registry).register(profile);
    order.verify(scheduler).registerProfile(profile);
    assertThat(view.name()).isEqualTo("weather");
    assertThat(view.providerName()).isEqualTo("deepseek");
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：重名在第一步就拒，一个目录都不写")
  void create_rejectsDuplicateBeforeWritingAnything() {
    when(registry.exists("weather")).thenReturn(true);

    assertThatThrownBy(() -> service.create("weather", "每天查天气"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Agent 已存在: 'weather'");

    verify(store, never()).scaffold(any(), any(), any(), any());
    verify(store, never()).writeAgentMarkdown(any(), any());
    verify(store, never()).writeAll(any(), any());
    verifyNoInteractions(llmClient);
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：注册失败必须回滚已写目录，系统里不留半个 Agent")
  void create_rollsBackWrittenDirectoryWhenRegisterFails() {
    Profile profile = profile("weather", List.of());
    when(store.scaffold("weather", "每天查天气", "deepseek", "deepseek-chat")).thenReturn(AGENT_DIR);
    when(profileLoader.deriveProfile(AGENT_DIR)).thenReturn(profile);
    doThrow(new IllegalStateException("注册炸了")).when(registry).register(profile);

    assertThatThrownBy(() -> service.create("weather", "每天查天气"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("注册炸了");

    verify(store).delete("weather");
    verify(scheduler, never()).registerProfile(any());
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：create 与 watcher 走同一段 register(agentDir)")
  void create_andWatcherShareTheSameRegisterPath() {
    AgentLifecycleService spied = spy(service);
    Profile profile = profile("weather", List.of());
    when(store.scaffold("weather", "每天查天气", "deepseek", "deepseek-chat")).thenReturn(AGENT_DIR);
    when(profileLoader.deriveProfile(AGENT_DIR)).thenReturn(profile);

    spied.create("weather", "每天查天气");

    // 「同一段代码」= 同一个公开方法、同一个入参；watcher 侧调用的也正是这个签名（WorkspaceWatcherTest 从另一头钉死）
    verify(spied).register(AGENT_DIR);
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：delete 顺序为「注销定时 → 移出索引 → 整目录归档」")
  void delete_unregistersThenRemovesThenArchives() {
    Profile profile = profile("weather", List.of(schedule("0 9 * * *")));
    when(registry.find("weather")).thenReturn(Optional.of(profile));
    when(store.archive("weather")).thenReturn(WORKSPACE.resolve("archive/weather"));

    service.delete("weather");

    InOrder order = inOrder(scheduler, registry, store);
    order.verify(scheduler).unregisterProfile(profile);
    order.verify(registry).remove("weather");
    order.verify(store).archive("weather");
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：update 改 schedules 必须先注销旧句柄再注册新的")
  void update_unregistersOldSchedulesBeforeRegisteringNew() {
    Profile old = profile("weather", List.of(schedule("0 9 * * *")));
    Profile updated =
        profile("weather", List.of(schedule("0 10 * * *")), "mock", "mock", List.of("save_memory"));
    String markdown = "---\nprovider:\n  name: mock\n---\n正文";
    when(registry.find("weather")).thenReturn(Optional.of(old));
    when(profileLoader.parse(markdown, "weather")).thenReturn(updated);
    when(toolCatalog.find("save_memory")).thenReturn(Optional.of(mock(OryxTool.class)));

    AgentView view = service.update("weather", markdown);

    InOrder order = inOrder(store, scheduler, registry);
    order.verify(store).writeAgentMarkdown("weather", markdown);
    order.verify(scheduler).unregisterProfile(old);
    order.verify(registry).register(updated);
    order.verify(scheduler).registerProfile(updated);
    assertThat(view.schedules()).hasSize(1);
    assertThat(view.tools()).containsExactly("save_memory");
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：update 引用未知厂商被拒，且一个字节都不写")
  void update_rejectsUnknownProviderBeforeWritingAnything() {
    Profile old = profile("weather", List.of());
    String markdown = "---\nprovider:\n  name: foo\n---\n正文";
    when(registry.find("weather")).thenReturn(Optional.of(old));
    when(profileLoader.parse(markdown, "weather"))
        .thenReturn(profile("weather", List.of(), "foo", "x"));

    assertThatThrownBy(() -> service.update("weather", markdown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知厂商: 'foo'")
        .hasMessageContaining("deepseek");

    verify(store, never()).writeAgentMarkdown(any(), any());
    verifyNoInteractions(llmClient);
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：update 引用未注册工具被拒，且一个字节都不写")
  void update_rejectsUnregisteredToolBeforeWritingAnything() {
    Profile old = profile("weather", List.of());
    String markdown = "---\ntools: [read_pdf]\n---\n正文";
    when(registry.find("weather")).thenReturn(Optional.of(old));
    when(profileLoader.parse(markdown, "weather"))
        .thenReturn(
            profile("weather", List.of(), "deepseek", "deepseek-chat", List.of("read_pdf")));
    when(toolCatalog.find("read_pdf")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update("weather", markdown))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("工具未注册: 'read_pdf'");

    verify(store, never()).writeAgentMarkdown(any(), any());
    verifyNoInteractions(llmClient);
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：创建后立刻出现在列表里（SC-001，不设时钟门槛）")
  void createIsImmediatelyVisibleInList() {
    ProfileRegistry liveRegistry = new ProfileRegistry(List.of());
    Profile profile = profile("weather", List.of());
    when(store.scaffold("weather", "每天查天气", "deepseek", "deepseek-chat")).thenReturn(AGENT_DIR);
    when(profileLoader.deriveProfile(AGENT_DIR)).thenReturn(profile);
    AgentLifecycleService live =
        new AgentLifecycleService(
            profileLoader,
            liveRegistry,
            scheduler,
            store,
            llmClient,
            toolCatalog,
            sessionService,
            agentService,
            List.of("deepseek", "mock"),
            "deepseek",
            "deepseek-chat");

    live.create("weather", "每天查天气");

    assertThat(live.list()).extracting(AgentView::name).containsExactly("weather");
    assertThat(liveRegistry.exists("weather")).isTrue();
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：作者 model 留空时脚手架用厂商名兜底（provider.model 必填，模板仍须可解析）")
  void createFallsBackToProviderNameWhenAuthorModelIsBlank() {
    when(store.scaffold("weather", "每天查天气", "deepseek", "deepseek")).thenReturn(AGENT_DIR);
    when(profileLoader.deriveProfile(AGENT_DIR)).thenReturn(profile("weather", List.of()));
    AgentLifecycleService withoutAuthorModel =
        new AgentLifecycleService(
            profileLoader,
            registry,
            scheduler,
            store,
            llmClient,
            toolCatalog,
            sessionService,
            agentService,
            List.of("deepseek", "mock"),
            "deepseek",
            "");

    withoutAuthorModel.create("weather", "每天查天气");

    verify(store).scaffold("weather", "每天查天气", "deepseek", "deepseek");
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：没有配置任何厂商时创建被拒（不得写出 provider 为空的模板）")
  void createRejectsWhenNoProviderConfigured() {
    AgentLifecycleService withoutProviders =
        new AgentLifecycleService(
            profileLoader,
            registry,
            scheduler,
            store,
            llmClient,
            toolCatalog,
            sessionService,
            agentService,
            List.of(),
            "",
            "");

    assertThatThrownBy(() -> withoutProviders.create("weather", "每天查天气"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有配置任何厂商");

    verify(store, never()).scaffold(any(), any(), any(), any());
    verifyNoInteractions(llmClient);
  }

  @Test
  @DisplayName("AgentLifecycleServiceTest：非法名称（含路径分隔符）在第一步就拒")
  void createRejectsIllegalName() {
    assertThatThrownBy(() -> service.create("../evil", "x"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Agent 名称非法");

    verifyNoInteractions(store, profileLoader, llmClient);
  }

  private static Profile profile(String name, List<Profile.ScheduleSpec> schedules) {
    return profile(name, schedules, "deepseek", "deepseek-chat", List.of());
  }

  private static Profile profile(
      String name, List<Profile.ScheduleSpec> schedules, String provider, String model) {
    return profile(name, schedules, provider, model, List.of());
  }

  private static Profile profile(
      String name,
      List<Profile.ScheduleSpec> schedules,
      String provider,
      String model,
      List<String> tools) {
    return new Profile(
        name,
        "每天查天气",
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec(provider, model, null),
        tools,
        List.of(),
        List.of(),
        schedules,
        List.of(),
        Profile.Settings.DEFAULT);
  }

  private static Profile.ScheduleSpec schedule(String cron) {
    return new Profile.ScheduleSpec(cron, "Asia/Shanghai", "查天气");
  }
}
