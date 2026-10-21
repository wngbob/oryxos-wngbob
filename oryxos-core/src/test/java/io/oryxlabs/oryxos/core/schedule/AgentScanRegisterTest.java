package io.oryxlabs.oryxos.core.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * 课件 harness「{@code AgentScanRegisterTest}」的落地（第 29 节）：N 个目录 → 索引出 N-1 个、失败项被点名、带 {@code schedules}
 * 的都进调度器。
 *
 * <p>这是 US1 + US2 的接缝测试：扫描（{@link ProfileLoader#loadAllDetailed()}）→ 建索引（{@link ProfileRegistry}）→
 * 注册定时（{@link AgentScheduler#registerAll()}）。三段都走真实实现，只有引擎与持久层是 mock——本测试判的是「目录到运行的这一段装配」， 不是引擎行为。
 */
class AgentScanRegisterTest {

  private static final String RECONCILE_FRONTMATTER =
      """
      ---
      description: 每日对账
      identity:
        agent_name: 对账小欧
        prompt: 只根据脚本给出的数据下结论。
      provider:
        name: deepseek
        model: deepseek-chat
      tools: [shell, read_file]
      schedules:
        - cron: "0 9 * * *"
          zone: Asia/Shanghai
          message: 早报
        - cron: "0 18 * * *"
          zone: Asia/Shanghai
          message: 晚报
      ---

      每天对一次账。
      """;

  private static final String QUIET_FRONTMATTER =
      """
      ---
      description: 只在被叫到时才动
      provider:
        name: deepseek
        model: deepseek-chat
      ---

      被问才答。
      """;

  /** 缺 {@code provider} 段：扫描必须点名跳过，且不阻断另外两个。 */
  private static final String BROKEN_FRONTMATTER =
      """
      ---
      description: 写坏的 Agent
      identity:
        agent_name: 坏掉的
      ---

      这行不会跑起来。
      """;

  @TempDir Path tempDir;

  private Path agentsDir;
  private TaskScheduler taskScheduler;

  /** 空环境变量表：{@code Map.of()::get} 的显式类型，避免 {@code Object} 返回值推断失败。 */
  private static final Map<String, String> NO_ENV = Map.of();

  private ScheduledFuture<?> handle;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = tempDir.resolve("agents");
    writeAgent("reconcile", RECONCILE_FRONTMATTER);
    writeAgent("quiet", QUIET_FRONTMATTER);
    writeAgent("broken", BROKEN_FRONTMATTER);

    taskScheduler = mock(TaskScheduler.class);
    handle = mock(ScheduledFuture.class);
    // 通配符返回类型配 thenReturn 会因为 capture# 编译不过，用 doReturn
    doReturn(handle).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("AgentScanRegisterTest：N 个目录 → 索引出 N-1 个，失败项被点名且不阻断其它")
  void scan_registersEveryValidAgentAndNamesTheBrokenOne() {
    ProfileLoader loader = loader();
    ProfileLoader.LoadResult result = loader.loadAllDetailed();

    assertThat(result.failures()).hasSize(1);
    assertThat(result.failures().get(0).agentName()).isEqualTo("broken");
    assertThat(result.failures().get(0).reason()).contains("缺少 provider 段");
    assertThat(result.loaded()).extracting(Profile::name).containsExactly("quiet", "reconcile");

    ProfileRegistry registry = new ProfileRegistry(result.loaded());
    assertThat(registry.names()).containsExactlyInAnyOrder("quiet", "reconcile");
    assertThat(registry.find("broken")).isEmpty();
    // 带 schedules 的原样进索引：两条，顺序不变
    assertThat(registry.find("reconcile").orElseThrow().schedules())
        .extracting(Profile.ScheduleSpec::message)
        .containsExactly("早报", "晚报");
  }

  @Test
  @DisplayName("AgentScanRegisterTest：带 schedules 的经 registerAll 后有句柄，不带的没有")
  void registerAll_createsHandlesOnlyForAgentsWithSchedules() {
    ProfileRegistry registry = new ProfileRegistry(loader().loadAllDetailed().loaded());
    AgentScheduler scheduler = scheduler(registry);

    scheduler.registerAll();

    assertThat(scheduler.scheduledTaskHandles()).containsOnlyKeys("reconcile#1", "reconcile#2");
    // quiet 没有任何 schedules：它不该在句柄表里、也不该被误注册成空规则
    assertThat(scheduler.scheduledTaskHandles()).doesNotContainKey("quiet#1");
  }

  @Test
  @DisplayName("AgentScanRegisterTest：运行时 deriveProfile 与扫描结果逐字段一致")
  void deriveProfile_matchesScannedProfile() {
    ProfileLoader loader = loader();
    Profile scanned =
        loader.loadAllDetailed().loaded().stream()
            .filter(profile -> profile.name().equals("reconcile"))
            .findFirst()
            .orElseThrow();

    Profile derived = loader.deriveProfile(agentsDir.resolve("reconcile"));

    assertThat(derived.name()).isEqualTo(scanned.name());
    assertThat(derived.description()).isEqualTo(scanned.description());
    assertThat(derived.provider()).isEqualTo(scanned.provider());
    assertThat(derived.tools()).isEqualTo(scanned.tools());
    assertThat(derived.schedules()).isEqualTo(scanned.schedules());
  }

  @Test
  @DisplayName("AgentScanRegisterTest：Agent 目录不存在时扫描为空、注册不抛异常")
  void scanOfMissingAgentsDir_yieldsNothing() {
    ProfileLoader loader =
        new ProfileLoader(tempDir.resolve("no-such-dir"), Set.of("deepseek"), NO_ENV::get);
    ProfileLoader.LoadResult result = loader.loadAllDetailed();

    assertThat(result.loaded()).isEmpty();
    assertThat(result.failures()).isEmpty();

    AgentScheduler scheduler = scheduler(new ProfileRegistry(List.of()));
    scheduler.registerAll();

    assertThat(scheduler.scheduledTaskHandles()).isEmpty();
  }

  private ProfileLoader loader() {
    return new ProfileLoader(agentsDir, Set.of("deepseek"), NO_ENV::get);
  }

  private AgentScheduler scheduler(ProfileRegistry registry) {
    return new AgentScheduler(
        taskScheduler,
        registry,
        mock(AgentService.class),
        mock(SessionService.class),
        mock(ScheduledTaskStore.class));
  }

  private void writeAgent(String name, String content) throws IOException {
    Path dir = agentsDir.resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
  }
}
