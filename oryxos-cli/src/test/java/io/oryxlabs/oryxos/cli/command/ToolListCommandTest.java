package io.oryxlabs.oryxos.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code oryxos tool list} 输出格式的守点（contracts/assembly.md §5.2）。
 *
 * <p><b>本类为什么必须存在</b>：本节交付时 {@code oryxos-cli/src/test} 只有契约测试、装配测试两个文件，§5.2
 * 的输出格式<b>一条断言都没有</b>。后果在 2026-10-15 的人工验收里现了形——MCP 子分组表头打成 {@code mcp (2):} 而不是 {@code mcp:time
 * (2):}，harness 全绿、肉眼才看得见。这正是「没有测试守着的格式 = 会悄悄坏掉的格式」。
 *
 * <p><b>为什么用反射调私有的 {@code print}</b>：要验的是「给定一批工具，输出长什么样」，而 {@code print} 是纯函数式的一层 ——不进 Spring
 * 上下文、不碰工作区、不查注册表。走 {@code run()} 得先起上下文再连一个真工作区，为验一个格式串付这个代价不值。 把 {@code print}
 * 放宽成包级私有也能测，但那会动已交付类的成员可见性；本节的口径是「对已交付类的触碰越小越好」， 故宁可让测试多一行反射。重命名 {@code print} 时本类会以 {@code
 * NoSuchMethodException} 报错，不会静默失效。
 */
class ToolListCommandTest {

  @Test
  @DisplayName("MCP 组内按 server 名分子表头：两个 server 各占一个表头，不是一个笼统的 mcp")
  void print_mcpToolsFromTwoServers_headsEachServerByName() {
    String output =
        render(
            List.of(
                tool("get_current_time", "mcp:time"),
                tool("convert_time", "mcp:time"),
                tool("create_issue", "mcp:github")));

    assertThat(output).contains("mcp:time (2):");
    assertThat(output).contains("mcp:github (1):");
    // 顶层表头是两空格缩进的 "  mcp (3):"，组内表头是四空格。这里断言四空格那一档不出现——
    // 它就是 2026-10-15 人工验收抓到的那个坏法（组内按前缀分，所有 server 塌成一个 mcp）
    assertThat(output).doesNotContain("\n    mcp (");
  }

  @Test
  @DisplayName("只有一个 MCP server 时表头也要带 server 名，不能退化成 mcp")
  void print_mcpToolsFromOneServer_headsByServerName() {
    String output =
        render(List.of(tool("get_current_time", "mcp:time"), tool("convert_time", "mcp:time")));

    assertThat(output).contains("mcp:time (2):");
    assertThat(output).doesNotContain("\n    mcp (");
  }

  @Test
  @DisplayName("builtin 组平铺，不再分子表头")
  void print_builtinGroup_isNotSubGrouped() {
    String output = render(List.of(tool("read_file", "builtin"), tool("grep", "builtin")));

    assertThat(output).contains("  builtin (2):");
    assertThat(output).doesNotContain("\n    builtin (");
    // 平铺组直接列条目，条目行以来源标签结尾
    assertThat(output).contains("read_file");
    assertThat(output).contains("builtin");
  }

  @Test
  @DisplayName("空组照样打印并注明原因——「annotation 0 个」是有用信息，不是噪音")
  void print_emptyAnnotationAndMcpGroups_printReasons() {
    String output = render(List.of(tool("read_file", "builtin")));

    assertThat(output).contains("  annotation (0):");
    assertThat(output).contains("（无）");
    assertThat(output).contains("  mcp (0):");
    assertThat(output).contains("mcp_servers.yaml");
  }

  @Test
  @DisplayName("首行给出总数，且组内条数之和等于总数")
  void print_header_showsTotalCount() {
    String output =
        render(
            List.of(
                tool("read_file", "builtin"),
                tool("my_tool", "annotation"),
                tool("get_current_time", "mcp:time")));

    assertThat(output).contains("Tool（共 3 个）:");
    assertThat(output).contains("  builtin (1):");
    assertThat(output).contains("  annotation (1):");
    assertThat(output).contains("  mcp (1):");
    assertThat(output).contains("mcp:time (1):");
  }

  /**
   * 反射调私有 {@code print}，并把 {@code System.out} 换成缓冲区以取回输出。
   *
   * <p>{@code System.out} 是全局的，故用 {@code finally} 还原——测试改了全局状态不还原，后面的测试会莫名其妙地打印不出来。
   */
  private static String render(List<ToolRegistry.RegisteredTool> entries) {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      Method print = ToolListCommand.class.getDeclaredMethod("print", List.class);
      print.setAccessible(true);
      System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
      print.invoke(null, entries);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("调不到 ToolListCommand.print", e);
    } finally {
      System.setOut(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  /** 最小工具替身：只带名字，行为不参与本类的断言。 */
  private static ToolRegistry.RegisteredTool tool(String name, String source) {
    return new ToolRegistry.RegisteredTool(
        new OryxTool() {
          @Override
          public String getName() {
            return name;
          }

          @Override
          public String getDescription() {
            return name + " 的用途";
          }

          @Override
          public String getInputSchema() {
            return "{}";
          }

          @Override
          public ToolResult execute(String inputJson) {
            return ToolResult.success(name);
          }
        },
        source);
  }
}
