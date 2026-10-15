package io.oryxlabs.oryxos.tool.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.oryxlabs.oryxos.tool.builtin.FileTools;
import io.oryxlabs.oryxos.tool.builtin.HttpTools;
import io.oryxlabs.oryxos.tool.interaction.InteractionTools;
import io.oryxlabs.oryxos.tool.search.WebSearchTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * FR-022 / SC-005 的守点：<b>加一个工具的成本恒定在「写一个类」</b>。
 *
 * <p>这条要求说的是「新工具不该让校验位、审计落库、按 Agent 过滤这三处代码发生任何改动」。它平时靠人 review 保证，但 review
 * 会漏——本类把它变成机器可判的：直接读那三处代码的<b>源文件</b>，断言里面<b>一个新增工具名都没有出现</b>。谁哪天为了某个工具在 {@code ToolExecutor} 里加一个
 * {@code if}，或者让 {@code ToolRegistry.availableFor} 认识某个工具名，这里立刻红。
 *
 * <p><b>为什么不直接跑 {@code git diff --stat}</b>（tasks.md T044 的原话）：diff 只对「当前工作区 vs 某个基线」有意义，而 测试要在任何一次
 * checkout、任何一台机器上都得出同一个结论。断言「这三处代码里不含任何工具名」是同一命题的<b>不依赖 git 状态</b>的
 * 等价形式——它恰好就是「加工具时这三处一行都不用改」的可判定表述。
 *
 * <p>另一半是正面控制：五个新工具确实各自挂在某一个工具宿主类上，且宿主类上确实有 {@code @Tool} 注解。没有这半条，把工具全删了 也能让上面那半条通过。
 */
class ToolOnboardingCostTest {

  /** 三处跨切面代码，各自代表一条「加工具不该碰」的路径。 */
  private static final List<String> CROSS_CUTTING =
      List.of(
          // 校验位：沙箱契约（工具在自己首行调 enforce，接口本身不因新工具而变）
          "oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/sandbox/Sandbox.java",
          // 审计落库：工具执行的唯一落点
          "oryxos-core/src/main/java/io/oryxlabs/oryxos/core/engine/ToolExecutor.java",
          // 按 Agent 过滤：声明清单 → 可用子集
          "oryxos-tool/src/main/java/io/oryxlabs/oryxos/tool/registry/ToolRegistry.java");

  /**
   * 本节新增的六个工具：FR-005 的五个，加交付后补强的 {@code web_fetch}（contracts/builtin-tools.md §2.12）。
   *
   * <p>{@code web_fetch} 是本次补进去的，正好可以当作这条守点的第二次检验——它加进了既有的 {@link HttpTools} 类， 却同样一行都没碰三处跨切面代码。
   */
  private static final List<String> NEW_TOOLS =
      List.of("edit_file", "grep", "glob", "ask_user", "web_search", "web_fetch");

  @Test
  @DisplayName("新增这五个工具没有触碰校验位 / 审计落库 / 按 Agent 过滤三处代码")
  void newTools_leaveTheThreeCrossCuttingSitesUntouched() {
    for (String relative : CROSS_CUTTING) {
      String source = read(relative);
      for (String tool : NEW_TOOLS) {
        assertThat(source)
            .as("%s 里出现了工具名 [%s]：加工具不该让这三处认识任何具体工具（FR-022）", relative, tool)
            .doesNotContain(tool);
      }
    }
  }

  @Test
  @DisplayName("正面控制：六个新工具各自挂在一个工具宿主类上，且宿主类带 @Tool 注解")
  void newTools_areHostedByPlainToolClasses() {
    assertThat(toolNamesOf(FileTools.class)).contains("edit_file", "grep", "glob");
    assertThat(toolNamesOf(InteractionTools.class)).contains("ask_user");
    assertThat(toolNamesOf(WebSearchTools.class)).contains("web_search");
    assertThat(toolNamesOf(HttpTools.class)).contains("http_get", "http_post", "web_fetch");
  }

  private static List<String> toolNamesOf(Class<?> host) {
    return Arrays.stream(host.getDeclaredMethods())
        .map(method -> method.getAnnotation(Tool.class))
        .filter(Objects::nonNull)
        .map(Tool::name)
        .toList();
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

  /**
   * 从当前工作目录（surefire 的 cwd 是模块目录）往上找仓库根。
   *
   * <p>找不到就<b>直接失败</b>，不静默跳过：静默跳过等于这条守点悄悄失效，而那正是它要防的事。
   */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-tool")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
