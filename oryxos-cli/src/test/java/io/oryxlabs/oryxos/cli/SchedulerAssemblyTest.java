package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务装配的硬约束（第 25 节）。
 *
 * <p><b>为什么必须起真上下文</b>：Bean 重复注册这类坏法只在真实启动流程里才现形（第 18 节有 {@code BeanDefinitionOverrideException}
 * 前科）——「{@code TaskScheduler} 类型的 Bean 恰好一个」这条断言 手工 new 是守不住的。
 *
 * <p><b>FR-012 为什么读源码不读行为</b>：Spring 6.2.19 的 {@code ThreadPoolTaskScheduler} <b>没有</b> {@code
 * getScheduledTasks()}（javap 实测），且 {@code profileRegistry()} 读的是 {@code Workspace.current()} （进程
 * cwd），单测造不出「有 schedules 的工作区」——两条行为判定的路都堵死，故按 {@code ToolOnboardingCostTest} / {@code
 * LogStreamSeparationTest} 的既有先例走源码判定。
 */
class SchedulerAssemblyTest {

  private static final String CLI_MAIN = "oryxos-cli/src/main/java/io/oryxlabs/oryxos/cli/";

  @Test
  @DisplayName("装配后容器里 AgentScheduler 类型的 Bean 恰好一个")
  void agentScheduler_hasExactlyOneBean(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      assertThat(context.getBeansOfType(AgentScheduler.class)).hasSize(1);
    }
  }

  @Test
  @DisplayName("调度线程池大小恰好是 2（FR-019：固定小池，不做配置键）")
  void taskScheduler_poolSizeIsExactlyTwo(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      // 「恰好 2」而不是「不超过 2」：池大小是本节唯一一处「跨任务并发度」的旋钮，改它就改行为，必须钉死。
      // 断 getCorePoolSize() 而非 getPoolSize()：后者在初始化后委托给 JDK 执行器的存活线程数——
      // 没跑过任务时恒为 0，断它会抓到一个假红（2026-10-18 实测）
      assertThat(
              context
                  .getBean(ThreadPoolTaskScheduler.class)
                  .getScheduledThreadPoolExecutor()
                  .getCorePoolSize())
          .isEqualTo(2);
    }
  }

  @Test
  @DisplayName("容器里 TaskScheduler 类型的 Bean 恰好一个（多一个候选会让池大小断言读到错的 Bean）")
  void taskSchedulerBean_hasExactlyOneCandidate(@TempDir Path tmp) {
    try (ConfigurableApplicationContext context = CliTestContext.boot(tmp.resolve("oryxos.db"))) {
      assertThat(context.getBeansOfType(TaskScheduler.class)).hasSize(1);
    }
  }

  @Test
  @DisplayName("registerAll 只在常驻命令（serve / gateway）被调用，chat 与装配处都不碰它（FR-012）")
  void scheduledRegistration_happensOnlyInResidentCommands() {
    assertThat(read(CLI_MAIN + "command/ServeCommand.java")).contains(".registerAll()");
    assertThat(read(CLI_MAIN + "command/GatewayCommand.java")).contains(".registerAll()");
    // 断言的是「调用形态」而非「单词出现」：javadoc 里提到 registerAll() 是文档，不是调用
    assertThat(read(CLI_MAIN + "command/ChatCommand.java")).doesNotContain(".registerAll()");
    assertThat(read(CLI_MAIN + "CliAssemblyConfiguration.java")).doesNotContain(".registerAll()");
  }

  @Test
  @DisplayName("实时监听只在常驻命令（serve / gateway）启动，chat 与装配处都不碰它（FR-003）")
  void workspaceWatcher_startsOnlyInResidentCommands() {
    // 断言调用形态而非单词 `.start(`：那条字符串在别处也会出现（线程、WatchService 自身），断它等于不断
    String startCall = ".getBean(WorkspaceWatcher.class).start()";
    assertThat(read(CLI_MAIN + "command/ServeCommand.java")).contains(startCall);
    assertThat(read(CLI_MAIN + "command/GatewayCommand.java")).contains(startCall);
    assertThat(read(CLI_MAIN + "command/ChatCommand.java")).doesNotContain("WorkspaceWatcher");
    // 装配处只造对象：在这里 start 一次，等于每个重命令都带上监听线程
    assertThat(read(CLI_MAIN + "CliAssemblyConfiguration.java")).doesNotContain(startCall);
  }

  private static String read(String relativePath) {
    Path file = repoRoot().resolve(relativePath);
    if (!Files.isRegularFile(file)) {
      fail("找不到源文件（仓库布局变了？）：" + file);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException("读不了源文件: " + file, e);
    }
  }

  /** 从当前工作目录（surefire 的 cwd 是模块目录）往上找仓库根。找不到就<b>直接失败</b>，不静默跳过： 静默跳过等于这条守点悄悄失效，而那正是它要防的事。 */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-boot")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
