package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「解释器只在管理员显式白名单内放行」的守点（第 29 节，FR-010）。
 *
 * <p><b>为什么需要它</b>：示例 Agent 的 {@code scripts/} 要靠 {@code shell} 工具跑 {@code python3}，而 {@code
 * shell.allowed_commands} 留空 = 全拒。少了这个取值，脚本那条链路<b>一行都跑不起来</b>，且失败形态是运行期的一条沙箱拒绝——
 * 不写这条，它只会在人工验收时才被发现。
 *
 * <p><b>为什么读源文件而不是注入属性</b>：{@code ToolAssemblyTest} 自己用 {@code Map.of("shell.allowed_commands[0]",
 * ...)} 注入属性源，它守的是「键名拼错」，<b>守不了「配置块被整体删掉」</b>（第 24
 * 节向导的回滚指令真把三块白名单还原掉过一次，那时全量测试照样全绿）。真正钉住随包默认值的只有「读这份文件本身」，与 {@link LogStreamSeparationTest} 是同一手法。
 *
 * <p><b>为什么落在 {@code oryxos-cli}</b>：{@code oryxos-boot} 零测试基建（与 {@link LogStreamSeparationTest}
 * 同一理由）。
 */
class ShellWhitelistSourceTest {

  /** 被守护的配置文件（仓库根相对路径）。 */
  private static final String APPLICATION_YAML = "oryxos-boot/src/main/resources/application.yaml";

  /** shell 块的开标签（顶格）。 */
  private static final String SHELL_BLOCK = "\nshell:\n";

  /** 紧邻 shell 块之后的 http 块开标签，用来把 shell 块切到它为止。 */
  private static final String HTTP_BLOCK = "\nhttp:\n";

  /** 本节的实体：Agent scripts 的解释器。 */
  private static final String PYTHON3 = "python3";

  @Test
  @DisplayName("shell.allowed_commands 含 python3 —— 删掉它，示例 Agent 的 scripts/ 脚本一行都跑不起来")
  void shellWhitelist_containsPython3() {
    assertThat(allowedCommands()).contains(PYTHON3);
  }

  @Test
  @DisplayName("正向对照：列表真的从 allowed_commands 那一行解析出来了，不是对注释里那串字的废话断言")
  void allowedCommands_areParsedFromTheValueLine() {
    // 没有这一条，上面那条可以在「解析失败 / 只命中块内注释里的 python3」时以错误的方式成立：
    // 注释里也写着 python3（说明为什么放行），从列表里删掉它有单靠 contains 抓不住
    assertThat(allowedCommands()).isNotEmpty().contains("ls").doesNotContain("#");
  }

  @Test
  @DisplayName("shell 块真的切出来了 —— 切不出来时本类每条断言都失去意义")
  void shellBlock_isCutOutCorrectly() {
    assertThat(shellBlock()).contains("allowed_commands").contains("留空 = 全拒");
  }

  /** shell 块内 {@code allowed_commands} 的取值列表（去掉行尾注释，不做任何归一化）。 */
  private static List<String> allowedCommands() {
    String block = shellBlock();
    String prefix = "allowed_commands:";
    String valueLine =
        block
            .lines()
            .filter(line -> !line.strip().startsWith("#"))
            .filter(line -> line.strip().startsWith(prefix))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "在 " + APPLICATION_YAML + " 的 shell 块里找不到 allowed_commands 行（键名被改了？）"));
    String value = valueLine.substring(valueLine.indexOf(prefix) + prefix.length());
    int commentAt = value.indexOf('#');
    if (commentAt >= 0) {
      value = value.substring(0, commentAt);
    }
    value = value.strip();
    if (!value.startsWith("[") || !value.endsWith("]")) {
      // 换成 YAML 列表（每行 - 一项）时这里会红：本类只认随包默认的行内写法，改形态请同步改本方法
      fail("shell.allowed_commands 不是行内列表写法，本类解析不了：%s", valueLine);
    }
    String items = value.substring(1, value.length() - 1);
    return Arrays.stream(items.split(","))
        .map(String::strip)
        .filter(item -> !item.isEmpty())
        .toList();
  }

  /** 文件里 shell 块的全部文本（从 {@code shell:} 到 {@code http:} 之前）。 */
  private static String shellBlock() {
    String source = "\n" + read(APPLICATION_YAML);
    int shellAt = source.indexOf(SHELL_BLOCK);
    int httpAt = source.indexOf(HTTP_BLOCK);
    if (shellAt < 0 || httpAt < 0 || httpAt <= shellAt) {
      fail(
          "在 %s 里找不到 shell / http 块（shell@%d, http@%d）。本类靠这两个顶格块名把文件切开，"
              + "改名会让下面每条断言失去意义——请同步更新 SHELL_BLOCK / HTTP_BLOCK 常量。",
          APPLICATION_YAML, shellAt, httpAt);
    }
    return source.substring(shellAt, httpAt);
  }

  private static String read(String relativePath) {
    Path file = repoRoot().resolve(relativePath);
    if (!Files.isRegularFile(file)) {
      fail("找不到配置文件（仓库布局变了？）：" + file);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new IllegalStateException("读不了配置文件: " + file, e);
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
                      && Files.isDirectory(dir.resolve("oryxos-boot")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
