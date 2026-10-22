package io.oryxlabs.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 实时监听的验收测试（第 30 节 FR-003 / 契约 §3）。
 *
 * <p><b>真 {@code WatchService} + 真临时目录</b>：这条能力本身就是「文件系统事件到达之后会发生什么」，把 {@code WatchService} 换成替身
 * 等于把被验的东西换成自己写的桩——它能证明连线对了，证明不了事件真的会来、真的会被重试。
 *
 * <p><b>轮询而不是 {@code Thread.sleep} 死等</b>：事件线程的调度是操作系统的事，写死一个 300ms 既可能太快（CI 上偶发红）也可能太慢。 上限 5
 * 秒（SC-002），过了才算失败。
 *
 * <p>{@link AgentScheduler} 用替身：本节关心的是「目录出现 / 消失时索引变没变」，排 cron 是第 29 节已判过的旧事；夹具里没有 {@code
 * schedules}，正常路径本来也走不到它。
 */
class WorkspaceWatcherTest {

  /** SC-002：目录变化到被登记，人工感知上限。轮询超过它即判红。 */
  private static final long POLL_TIMEOUT_MILLIS = 5_000;

  /** 轮询间隔：够密（不拖慢用例）又够松（不空转打满一个核）。 */
  private static final long POLL_INTERVAL_MILLIS = 50;

  @TempDir Path tempDir;

  private Path agentsDir;
  private ProfileRegistry registry;
  private WorkspaceWatcher watcher;

  @BeforeEach
  void setUp() throws IOException {
    agentsDir = Files.createDirectories(tempDir.resolve("agents"));
    ProfileLoader loader = new ProfileLoader(agentsDir, Set.of("deepseek", "mock"), name -> null);
    registry = new ProfileRegistry(List.of());
    AgentLifecycleService lifecycle =
        new AgentLifecycleService(
            loader,
            registry,
            mock(AgentScheduler.class),
            mock(AgentStore.class),
            mock(LlmClient.class),
            mock(ToolCatalog.class),
            mock(SessionService.class),
            mock(AgentService.class),
            List.of("deepseek", "mock"),
            "deepseek",
            "deepseek-chat");
    watcher = new WorkspaceWatcher(lifecycle, agentsDir);
  }

  @AfterEach
  void tearDown() {
    // 即使某条用例中途失败也要收线程：daemon 线程不影响 JVM 退出，但会继续消费临时目录上的事件、干扰后续用例
    watcher.stop();
  }

  @Test
  @DisplayName("启动先全量扫一遍：进程起之前就在的 Agent 也要被登记")
  void startupScanRegistersExistingAgents() throws IOException {
    writeAgent("weather");

    watcher.start();

    awaitRegistered("weather");
  }

  @Test
  @DisplayName("手工丢一个目录进来，免重启即可见")
  void handDroppedDirectoryBecomesVisibleWithoutRestart() throws IOException {
    watcher.start();
    assertThat(registry.exists("weather")).as("夹具前提：启动时还没有这个 Agent").isFalse();

    writeAgent("weather");

    awaitRegistered("weather");
  }

  @Test
  @DisplayName("删掉目录 → 从运行时索引里注销")
  void removedDirectoryIsUnregistered() throws IOException {
    writeAgent("weather");
    watcher.start();
    awaitRegistered("weather");

    deleteRecursively(agentsDir.resolve("weather"));

    await(() -> !registry.exists("weather"), "weather 应当已被注销");
  }

  @Test
  @DisplayName("坏目录只告警跳过，不拖垮监听线程：随后的好目录照常登记")
  void brokenDirectoryDoesNotKillTheWatcherThread() throws IOException {
    watcher.start();

    // 坏目录：frontmatter 缺 provider 段，deriveProfile 必抛
    Path broken = Files.createDirectories(agentsDir.resolve("broken"));
    Files.writeString(
        broken.resolve("AGENT.md"),
        "---\ndescription: 没有 provider\n---\n正文\n",
        StandardCharsets.UTF_8);

    // 给它足够的时间把重试队列耗掉（10 × 300ms ≈ 3s 之内不会成功），再丢一个好目录
    writeAgent("weather");

    awaitRegistered("weather");
    assertThat(registry.exists("broken")).as("坏目录 MUST NOT 进索引").isFalse();
  }

  @Test
  @DisplayName("先建目录、后落 AGENT.md（cp -a 的竞态）：重试后仍能登记")
  void directoryCreatedBeforeAgentMarkdownIsRetried() throws IOException {
    watcher.start();

    // cp -a 的形状：目录先到，AGENT.md 稍后才写完
    Path dir = Files.createDirectories(agentsDir.resolve("weather"));
    sleepQuietly(300);
    Files.writeString(dir.resolve("AGENT.md"), agentMarkdown("weather"), StandardCharsets.UTF_8);

    awaitRegistered("weather");
  }

  /** 轮询到索引里出现为止；超时即判红（`assertThat` 的失败消息会带上 Agent 名）。 */
  private void awaitRegistered(String name) {
    await(() -> registry.exists(name), "Agent 应当在 " + POLL_TIMEOUT_MILLIS + "ms 内被登记: " + name);
  }

  /** 有上限的轮询：SC-002 说的是「几秒内可见」，不是「睡够时间」。 */
  private static void await(BooleanSupplier condition, String expectation) {
    long deadline = System.nanoTime() + POLL_TIMEOUT_MILLIS * 1_000_000L;
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
    assertThat(condition.getAsBoolean()).as(expectation).isTrue();
  }

  private void writeAgent(String name) throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), agentMarkdown(name), StandardCharsets.UTF_8);
  }

  private static String agentMarkdown(String name) {
    return "---\n"
        + "description: 测试 Agent\n"
        + "provider:\n"
        + "  name: mock\n"
        + "  model: mock\n"
        + "---\n\n"
        + "你是 "
        + name
        + " 助手。\n";
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      List<Path> all = paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
      for (Path path : all) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      // 恢复中断位再上抛成运行时异常：测试线程被打断不该被当成「条件不满足」静默吞掉
      Thread.currentThread().interrupt();
      throw new IllegalStateException("轮询被中断", e);
    }
  }
}
