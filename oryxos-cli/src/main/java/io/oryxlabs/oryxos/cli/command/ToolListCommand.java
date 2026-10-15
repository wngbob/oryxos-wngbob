package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.CliApplication;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;

/**
 * {@code oryxos tool list}：列出全量 Tool，按来源分组（FR-002、contracts/cli-surface.md
 * §2.10、contracts/assembly.md §5）。
 *
 * <p><b>本节由「轻」改「重」</b>（contracts/assembly.md §5.1）：它现在要起 Spring 上下文，从 {@link ToolRegistry} 取数。
 *
 * <p><b>为什么算重命令</b>：§1 的判据是「这条命令要不要调模型、跑引擎」。列出 MCP 工具<b>必须连上 server</b>——MCP
 * 工具的清单只有连上才知道，这已经是「跑引擎」。第 18 节把它归轻，是因为当时<b>没有注册表可查</b>（接缝是空实现），不是判据不同。
 *
 * <p><b>代价如实记录</b>：有 MCP server 声明时，本命令最多等待 `10 秒 × server 数`（FR-009 的有界等待）。
 *
 * <p><b>命令名 / 参数 / 退出码三项不变</b>——契约 §1 要求走修订程序的正是这三项，本次改动一个都没碰，故在节内权限范围内。
 *
 * <p><b>空组照样打印</b>：「annotation 0 个」是<b>有用信息</b>（说明业务方还没写工具），不是噪音；MCP 空组还要注明原因 （默认配置下 {@code
 * mcp_servers.yaml} 是空清单，见 contracts/mcp-integration.md §1.1）。
 *
 * <p><b>不编造工具</b>：数据只有注册表一个来源。为保住「轻」而另造一份静态工具清单，等于第二处真相，FR-022 当场不满足 （contracts/assembly.md §6.6）。
 */
@Command(name = "list", mixinStandardHelpOptions = true, description = "列出可用 Tool（全量，按来源分组）")
public final class ToolListCommand implements Runnable {

  /**
   * 三档来源的分组标签（contracts/assembly.md §5.2）。
   *
   * <p>各单列成常量而不是就地写字符串：{@code mcp} 这一档在「分组顺序」「空组说明」「组内是否再按 server 分一层」三处都要用到， 三处必须同源——一处拼错就是「MCP
   * 组静默不打说明行」这种只有肉眼看输出才发现的错。
   */
  private static final String GROUP_BUILTIN = "builtin";

  /** 见 {@link #GROUP_BUILTIN}。 */
  private static final String GROUP_ANNOTATION = "annotation";

  /** 见 {@link #GROUP_BUILTIN}。 */
  private static final String GROUP_MCP = "mcp";

  /** 分组顺序固定：内置 → 注解 Bean → MCP。与注册顺序一致，读起来才是同一条线。 */
  private static final List<String> GROUP_ORDER =
      List.of(GROUP_BUILTIN, GROUP_ANNOTATION, GROUP_MCP);

  /** 各组为空时的说明；{@code null} 表示不打说明行（见 §5.2 的三种空组形态）。 */
  private static final Map<String, String> EMPTY_NOTES =
      Map.of(GROUP_ANNOTATION, "（无）", GROUP_MCP, "（无：.oryxos/mcp_servers.yaml 里没有声明任何 server）");

  /** 名称列的最小宽度；比最长的工具名还短时按最长名撑开。 */
  private static final int MIN_NAME_WIDTH = 15;

  /** 说明列的最小宽度；同上。 */
  private static final int MIN_DESCRIPTION_WIDTH = 30;

  @Override
  public void run() {
    // 与 chat 同款前置：工作区检查必须在起上下文之前。application.yaml 的 datasource url 指向
    // .oryxos/oryxos.db，连一个不存在的库会把它悄悄建出来，于是「没跑过 init」变成一个空库而不是一句清楚的提示
    Workspace.current().requireExists();
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(CliApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      print(context.getBean(ToolRegistry.class).registered());
    }
  }

  /** 按来源分组打印；列顺序按冻结契约：name、description、来源（cli-surface.md §2.10）。 */
  private static void print(List<ToolRegistry.RegisteredTool> entries) {
    System.out.println("Tool（共 " + entries.size() + " 个）:");
    int nameWidth = width(entries, MIN_NAME_WIDTH, entry -> entry.tool().getName());
    int descriptionWidth = width(entries, MIN_DESCRIPTION_WIDTH, ToolListCommand::description);
    Map<String, List<ToolRegistry.RegisteredTool>> byGroup = group(entries);
    for (String group : GROUP_ORDER) {
      List<ToolRegistry.RegisteredTool> groupEntries = byGroup.getOrDefault(group, List.of());
      System.out.println("  " + group + " (" + groupEntries.size() + "):");
      if (groupEntries.isEmpty()) {
        String note = EMPTY_NOTES.get(group);
        if (note != null) {
          System.out.println("    " + note);
        }
        continue;
      }
      printGroup(group, groupEntries, nameWidth, descriptionWidth);
    }
  }

  /** {@code mcp} 组内再按 {@code mcp:<server名>} 分一次，其余组平铺。 */
  private static void printGroup(
      String group,
      List<ToolRegistry.RegisteredTool> groupEntries,
      int nameWidth,
      int descriptionWidth) {
    if (!GROUP_MCP.equals(group)) {
      for (ToolRegistry.RegisteredTool entry : groupEntries) {
        System.out.println(row(entry, nameWidth, descriptionWidth, "    "));
      }
      return;
    }
    Map<String, List<ToolRegistry.RegisteredTool>> byServer = bySource(groupEntries);
    for (Map.Entry<String, List<ToolRegistry.RegisteredTool>> server : byServer.entrySet()) {
      System.out.println("    " + server.getKey() + " (" + server.getValue().size() + "):");
      for (ToolRegistry.RegisteredTool entry : server.getValue()) {
        System.out.println(row(entry, nameWidth, descriptionWidth, "      "));
      }
    }
  }

  private static String row(
      ToolRegistry.RegisteredTool entry, int nameWidth, int descriptionWidth, String indent) {
    // 手工补齐而不是 String.format("%-Ns")：列宽要拼进格式串里才生效，而格式串一旦不是编译期常量，
    // FindSecBugs 就按 FORMAT_STRING_MANIPULATION 报「格式串可由外部影响」——工具名与说明都来自注册表
    // （MCP 那一档直接来自外部 server），这个告警值得从根上消掉，而不是加一条豁免
    return indent
        + pad(entry.tool().getName(), nameWidth)
        + "  "
        + pad(description(entry), descriptionWidth)
        + "  "
        + entry.source();
  }

  /** 右补空格到 {@code width}；本身更长的原样返回。与 {@code %-Ns} 同口径——补空格、不截断。 */
  private static String pad(String text, int width) {
    return text.length() >= width ? text : text + " ".repeat(width - text.length());
  }

  /**
   * 顶层分组：键 = 来源的<b>前缀</b>，{@code mcp:github-mcp} → {@code mcp}；{@code builtin} → {@code builtin}。
   *
   * <p><b>只用于顶层三组</b>，MCP 组内那一层必须用 {@link #bySource}——见该方法的说明。
   */
  private static Map<String, List<ToolRegistry.RegisteredTool>> group(
      List<ToolRegistry.RegisteredTool> entries) {
    Map<String, List<ToolRegistry.RegisteredTool>> byGroup = new LinkedHashMap<>();
    for (ToolRegistry.RegisteredTool entry : entries) {
      byGroup.computeIfAbsent(prefix(entry.source()), key -> new ArrayList<>()).add(entry);
    }
    return byGroup;
  }

  /**
   * MCP 组内的分组：键 = 来源的<b>全串</b>，{@code mcp:time} → {@code mcp:time}。
   *
   * <p><b>这里不能用 {@link #group}</b>：组内每个条目的前缀都已经等于 {@code mcp}，按前缀分只会得到唯一一个键 {@code mcp}，表头退化成
   * {@code mcp (2):}——多个 server 的工具挤在一个表头下，谁也分不出哪个工具来自哪个 server （契约 §5.2 要的是 {@code
   * mcp:<server名>}）。两处看着只差一个 {@code prefix(...)}，却是「分开了」与「没分开」的分界。
   */
  private static Map<String, List<ToolRegistry.RegisteredTool>> bySource(
      List<ToolRegistry.RegisteredTool> entries) {
    Map<String, List<ToolRegistry.RegisteredTool>> bySource = new LinkedHashMap<>();
    for (ToolRegistry.RegisteredTool entry : entries) {
      bySource.computeIfAbsent(entry.source(), key -> new ArrayList<>()).add(entry);
    }
    return bySource;
  }

  private static String prefix(String source) {
    int colon = source.indexOf(':');
    return colon < 0 ? source : source.substring(0, colon);
  }

  /** MCP 工具的说明由外部 server 给，可能为空——列宽与打印都要能容下。 */
  private static String description(ToolRegistry.RegisteredTool entry) {
    String description = entry.tool().getDescription();
    return description == null ? "" : description;
  }

  private static int width(
      List<ToolRegistry.RegisteredTool> entries,
      int minimum,
      java.util.function.Function<ToolRegistry.RegisteredTool, String> field) {
    int width = minimum;
    for (ToolRegistry.RegisteredTool entry : entries) {
      width = Math.max(width, field.apply(entry).length());
    }
    return width;
  }
}
