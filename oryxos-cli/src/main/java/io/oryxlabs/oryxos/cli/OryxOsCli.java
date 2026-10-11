package io.oryxlabs.oryxos.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * OryxOS 命令行主入口（Picocli）。
 *
 * <p>当前能力：无参数运行打印版本信息；{@code -V} / {@code --version} 打印版本号； {@code -h} / {@code --help} 打印用法。12
 * 个子命令（init / status / chat / serve / gateway / profile * / provider list / tool list / session
 * list）随 user story 落地， 见 CLAUDE.md「命令行工具」一节。
 *
 * <p>设计要点：CLI 是 fat JAR 的唯一入口；不需要 Spring 上下文的命令（init、profile list） 直接走文件操作保证启动速度，需要 LLM 的命令（chat /
 * serve / gateway）才启动 Spring 上下文。
 */
@Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    versionProvider = OryxOsCli.VersionProvider.class,
    description = "OryxOS —— 企业 Agent 操作系统（Agent Harness OS）命令行工具",
    synopsisHeading = "用法: ",
    descriptionHeading = "%n描述:%n",
    optionListHeading = "%n选项:%n")
public final class OryxOsCli implements Runnable {

  /** 版本标语（无子命令时的默认行为）。 */
  @Override
  public void run() {
    System.out.println("OryxOS " + VersionProvider.loadVersion());
    System.out.println("企业 Agent 操作系统（Agent Harness OS）—— 让每一家公司，都能用自然语言跑起来自己的 Agent。");
    System.out.println("运行 'oryxos --help' 查看可用命令（子命令随 user story 逐步落地）。");
  }

  public static void main(String[] args) {
    // Windows 上 System.out 默认走控制台代码页（GBK），中文在非 GBK 终端会乱码；
    // 统一强制 UTF-8 输出（现代终端均为 UTF-8）
    System.setOut(
        new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    System.setErr(
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    System.exit(new CommandLine(new OryxOsCli()).execute(args));
  }

  /**
   * 版本号来源：classpath 上的 {@code oryxos-version.properties}（Maven 构建时注入 {@code
   * project.version}），读取失败回退 {@code dev}，绝不硬编码版本号。
   */
  public static final class VersionProvider implements CommandLine.IVersionProvider {

    private static final String UNKNOWN = "dev";

    @Override
    public String[] getVersion() {
      return new String[] {loadVersion()};
    }

    static String loadVersion() {
      try (InputStream in = OryxOsCli.class.getResourceAsStream("/oryxos-version.properties")) {
        if (in != null) {
          Properties props = new Properties();
          props.load(in);
          String version = props.getProperty("version");
          if (version != null && !version.isBlank()) {
            return version;
          }
        }
      } catch (IOException ignored) {
        // 回退 dev
      }
      return UNKNOWN;
    }
  }
}
