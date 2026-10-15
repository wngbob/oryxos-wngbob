package io.oryxlabs.oryxos.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 课件验收 harness 第一条，也是<b>最值钱的一条</b>：遍历装配后的真实注册表，断言每个工具都具备名字 / 用途说明 / 入参 Schema
 * 三件套（contracts/builtin-tools.md §3.1；SC-001）。
 *
 * <p><b>为什么必须取「装配后的真实注册表」</b>：新工具是自动纳入检查的——本节之后任何一个工具漏了 Schema，这里立刻红，不需要 谁记得补一条测试。这正是
 * FR-022「加工具的成本恒定」在测试侧的样子。手工 {@code new} 一个注册表再塞几个工具，等于把「新工具会不会 被漏掉」这个要守的问题绕过去。
 *
 * <p><b>为什么在 {@code oryxos-cli}</b>：真实注册表由 {@code CliAssemblyConfiguration} 装配出来，只有本模块同时看得见装配与
 * 全部工具模块。放在 {@code oryxos-tool} 里就得手工拼一个「像装配那样」的注册表，那不是装配后的真实注册表。
 *
 * <p><b>上下文只起一次</b>：参数化测试有十几个 invocation，逐个起上下文既慢又没多验到任何东西。启动被收在一个惰性访问器里， 因此 {@code @MethodSource}
 * 与 {@code @AfterAll} 的先后顺序不会影响结果。
 */
class OryxToolContractTest {

  private static ConfigurableApplicationContext context;

  private static Path dbFile;

  static Stream<OryxTool> allRegisteredTools() {
    return registry().registered().stream().map(ToolRegistry.RegisteredTool::tool);
  }

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("每个已注册工具都必须具备名字、用途说明、入参 Schema 三件套")
  void everyRegisteredTool_exposesCompleteContract(OryxTool tool) {
    assertThat(tool.getName()).isNotBlank();
    assertThat(tool.getDescription()).isNotBlank();
    assertThat(tool.getInputSchema()).isNotBlank();
  }

  @Test
  @DisplayName("13 个工具全部在册，且第 19 节的 notify 确实由本节完成注册")
  void allThirteenToolsAreRegistered_includingNotifyFromLesson19() {
    List<String> names =
        registry().registered().stream().map(entry -> entry.tool().getName()).toList();

    // 下限而非等于：第 22 节的记忆工具、以及任何 MCP server 的工具都会让这个数变大，那时这条仍然成立
    assertThat(names).hasSizeGreaterThanOrEqualTo(13);
    assertThat(names).contains("notify");
  }

  /** 惰性启动：{@code @MethodSource} 何时被求值不影响到这里的结果。 */
  private static synchronized ToolRegistry registry() {
    if (context == null) {
      try {
        dbFile = Files.createTempFile("oryxos-contract-", ".db");
      } catch (IOException e) {
        throw new IllegalStateException("建不了临时库文件", e);
      }
      context = CliTestContext.boot(dbFile);
    }
    return context.getBean(ToolRegistry.class);
  }

  @AfterAll
  static void shutdown() {
    if (context != null) {
      context.close();
    }
  }
}
