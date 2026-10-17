package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「命令输出走 stdout、日志走 stderr」的守点（2026-10-15）。
 *
 * <p><b>为什么需要它</b>：logback 的 {@code ConsoleAppender} <b>默认 target 就是 {@code System.out}</b>，而 CLI
 * 命令的输出 （{@code System.out.println}）也在 {@code System.out} 上——两者同一条流。第 20 节把 {@code tool list}
 * 从轻命令改成重命令 （要连 MCP server 才能列出工具清单）后，实测表格前有 <b>24 行</b> Spring / Hibernate 启动日志，于是 {@code oryxos
 * tool list | head -20} <b>一行工具都打不出来</b>、{@code | grep} 之类的管道消费方全被污染。
 *
 * <p><b>为什么不能靠跑测试发现</b>：{@link ToolListCommandTest} 用<b>反射直接调私有的 {@code print}</b>，根本不进 Spring
 * 上下文（它这么写是有意的），所以那个类永远绿、而终端噪音一直在。真正能抓住这条的只有「读配置文件本身」，本类就是干这个的。
 *
 * <p><b>为什么放在 {@code oryxos-cli} 而不是配置文件所在的 {@code oryxos-boot}</b>：{@code oryxos-boot}
 * 目前<b>零测试基建</b> （没有 test 源根、没有 test 依赖），为一个四条断言的守点给它新开 test 源根 + 加测试依赖，比被守护的改动本身还重。而本类护的是 <b>CLI
 * 的输出契约</b>——谁的命令输出被日志污染，谁就该守着这条线。
 *
 * <p><b>为什么断言的是配置文本而不是运行期行为</b>：能验行为的地方是 {@code oryxos-boot} 自己的测试（只有它的 classpath 上有这份 {@code
 * logback-spring.xml}；{@code oryxos-cli} 的测试 classpath 上没有，Spring Boot 会退回默认配置，那验的是<b>另一套</b>
 * 配置）。退而求其次断言「这份配置里写着什么」，与 {@code ToolOnboardingCostTest} 读源文件是同一手法。
 */
class LogStreamSeparationTest {

  /** 被守护的配置文件（仓库根相对路径）。 */
  private static final String LOGBACK = "oryxos-boot/src/main/resources/logback-spring.xml";

  /** 两个 profile 块的开标签，用来把文件切成互不重叠的两半。 */
  private static final String DEV_PROFILE = "<springProfile name=\"!prod\">";

  /** 见 {@link #DEV_PROFILE}。 */
  private static final String PROD_PROFILE = "<springProfile name=\"prod\">";

  /** 这一行就是本次修复的实体——删了它，上面那 24 行日志会原样回到 stdout 上。 */
  private static final String STDERR_TARGET = "<target>System.err</target>";

  @Test
  @DisplayName("dev 档的 CONSOLE appender 显式把日志打到 stderr —— 删掉这一行管道消费方就会被日志污染")
  void devProfile_writesLogsToStderr() {
    assertThat(devBlock()).contains(STDERR_TARGET);
  }

  @Test
  @DisplayName("prod 档的 JSON appender 必须留在 stdout —— 容器日志采集侧读的是 stdout，不能跟着改")
  void prodProfile_staysOnStdout() {
    assertThat(prodBlock()).doesNotContain("System.err");
  }

  @Test
  @DisplayName("正向对照：两个 profile 块都真的切出来了，不是两条对整份文件的废话断言")
  void profileBlocks_areCutOutCorrectly() {
    // 没有这一条，上面两条可以在「切分失败」时以错误的方式成立：dev 块若等于整份文件，「含 System.err」恒真；
    // prod 块若为空，「不含 System.err」也恒真——两条一起变成永远绿的摆设
    // 用 "<encoder" 而不是 "<encoder>"：prod 档那个 encoder 带 class 属性（LogstashEncoder），字面量闭合标签匹配不上
    assertThat(devBlock()).contains("CONSOLE").contains("<encoder").contains("level=");
    assertThat(prodBlock()).contains("JSON_CONSOLE").contains("<encoder").contains("level=");
  }

  @Test
  @DisplayName("dev 档根级别没被压到 ERROR/OFF —— WARN 级诊断 MUST NOT 被静音")
  void devRootLevel_keepsWarnVisible() {
    // 守的是通用契约：WARN 及以下的诊断在 dev 档要看得见。把根级别压到 WARN 之上，等于把「出了事有留痕」这件事悄悄拆掉
    String dev = devBlock();
    assertThat(dev).contains("<root level=");
    assertThat(dev).doesNotContain("level=\"OFF\"");
    assertThat(dev).doesNotContain("level=\"ERROR\"");
  }

  /** 文件的前半：从 dev 块开头到 prod 块开头。 */
  private static String devBlock() {
    return split()[0];
  }

  /** 文件的后半：从 prod 块开头到文件尾。 */
  private static String prodBlock() {
    return split()[1];
  }

  /**
   * 按两个 profile 开标签把文件切成两半。
   *
   * <p><b>切不出来就直接失败，不静默退化</b>：profile 一旦改名，{@code indexOf} 返回 {@code -1}，若放任不管， {@code substring(0,
   * -1)} 会抛一个看不出所以然的 {@code StringIndexOutOfBoundsException}，或者更糟——被谁「顺手」兜住之后变成 对整份文件的检查，那时「prod
   * 档不含 {@code System.err}」会被 dev 档那一行打红（假红），而「dev 档含 {@code System.err}」
   * 会变成一条永远成立的废话（假绿）。两头都坏，故这里给一条能看懂为什么失败的错。
   */
  private static String[] split() {
    String source = read(LOGBACK);
    int devAt = source.indexOf(DEV_PROFILE);
    int prodAt = source.indexOf(PROD_PROFILE);
    if (devAt < 0 || prodAt < 0 || prodAt <= devAt) {
      fail(
          "在 %s 里找不到两个 springProfile 块（dev@%d, prod@%d）。本类靠这两个开标签把文件切成两半，"
              + "改名会让下面每条断言失去意义（详见本方法 javadoc）——请同步更新 DEV_PROFILE / PROD_PROFILE 常量。",
          LOGBACK, devAt, prodAt);
    }
    return new String[] {source.substring(devAt, prodAt), source.substring(prodAt)};
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
