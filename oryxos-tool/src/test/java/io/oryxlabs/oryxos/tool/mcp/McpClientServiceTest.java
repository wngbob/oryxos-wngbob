package io.oryxlabs.oryxos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link McpClientService} 的失败隔离（contracts/mcp-integration.md §3.3；FR-010 / SC-004）。
 *
 * <p>配置走<b>真的</b> {@link McpServerConfigLoader}（写一份真的 {@code mcp_servers.yaml} 到临时目录），只有客户端是替身——
 * 于是「读配置 → 连 server → 包装注册」这条链路上，唯一被替换掉的是不可控的外部进程。
 *
 * <p><b>「有界等待」这条断言的口径，说清楚免得误读</b>：10 秒上限本身是 SDK 的 {@code initializationTimeout} / {@code
 * requestTimeout} 在管，单测里量不到那个字面量——真去量就得起一个「连上但不说话」的 server 干等 10 秒，那是集成测试的形态。
 * 这里钉的是<b>调用方侧</b>的性质：握手失败时本类不追加自己的无界等待、不让异常逃出去、后面的 server 照常处理。
 */
class McpClientServiceTest {

  @TempDir Path oryxosDir;

  private static McpSchema.Tool tool(String name) {
    return McpSchema.Tool.builder()
        .name(name)
        .description("替身工具 " + name)
        .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
        .build();
  }

  private static McpSyncClient clientReporting(String... toolNames) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(toolNames).stream().map(McpClientServiceTest::tool).toList(), null));
    return client;
  }

  /** 一个「握手时超时」的替身：睡一小会儿再抛，模拟 SDK 的 initializationTimeout 到点后的形态。 */
  private static McpSyncClient timingOutClient() {
    McpSyncClient client = mock(McpSyncClient.class);
    doAnswer(
            invocation -> {
              Thread.sleep(200);
              throw new IllegalStateException("初始化超时");
            })
        .when(client)
        .initialize();
    return client;
  }

  private void writeConfig(String yaml) throws IOException {
    Files.writeString(oryxosDir.resolve("mcp_servers.yaml"), yaml, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("某个 MCP server 失联，不能拖垮启动和其他工具")
  void whenOneMcpServerIsUnreachable_otherToolsStillRegistered() throws IOException {
    // server A 正常、server B 失联
    writeConfig(
        """
        servers:
          - name: good
            transport: stdio
            command: [fake-mcp-server, --good]
          - name: bad
            transport: stdio
            command: [fake-mcp-server, --bad]
        """);
    Function<McpServerConfig, McpSyncClient> factory =
        config -> {
          if ("bad".equals(config.name())) {
            throw new IllegalStateException("连不上");
          }
          return clientReporting("a_tool");
        };
    McpClientService service = new McpClientService(new McpServerConfigLoader(oryxosDir), factory);
    ToolRegistry registry = new ToolRegistry();

    // 关键：不失联的那个 server 自己抛不抛异常，是这条用例的第一断言——下面整段都不许抛
    assertThatCode(() -> service.connectAll(registry)).doesNotThrowAnyException();

    assertThat(registry.contains("a_tool")).isTrue();
    assertThat(registry.contains("b_tool")).isFalse();
  }

  @Test
  @DisplayName("MCP 工具的来源标签是 mcp:<server 名>（重名时按先注册者胜出，靠它区分）")
  void registeredMcpTools_carryServerNameAsSource() throws IOException {
    writeConfig(
        """
        servers:
          - name: github-mcp
            transport: stdio
            command: [fake-mcp-server]
        """);
    McpClientService service =
        new McpClientService(
            new McpServerConfigLoader(oryxosDir), config -> clientReporting("list_prs"));
    ToolRegistry registry = new ToolRegistry();

    service.connectAll(registry);

    assertThat(registry.registered())
        .singleElement()
        .extracting(ToolRegistry.RegisteredTool::source)
        .isEqualTo("mcp:github-mcp");
  }

  @Test
  @DisplayName("握手失败的 server 不拖住启动：WARN 一条，后面的 server 照常注册")
  void connectAll_serverHandshakeFails_warnsAndContinues() throws IOException {
    writeConfig(
        """
        servers:
          - name: slow
            transport: stdio
            command: [fake-mcp-server, --slow]
          - name: good
            transport: stdio
            command: [fake-mcp-server, --good]
        """);
    Function<McpServerConfig, McpSyncClient> factory =
        config -> "slow".equals(config.name()) ? timingOutClient() : clientReporting("a_tool");
    McpClientService service = new McpClientService(new McpServerConfigLoader(oryxosDir), factory);
    ToolRegistry registry = new ToolRegistry();
    ListAppender<ILoggingEvent> appender = attachAppender();

    long startNanos = System.nanoTime();
    service.connectAll(registry);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

    assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    assertThat(registry.contains("a_tool")).isTrue();
    List<ILoggingEvent> warns =
        appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertThat(warns).hasSize(1);
    // 日志是结构化口径（第 16/17 节同款）：server 名落在字段里，而不是拼进文案——断言字段比断言文案更强，
    // 且文案改了这条也不会假绿
    assertThat(structuredValue(warns.get(0), "server")).isEqualTo("slow");

    detachAppender(appender);
  }

  @Test
  @DisplayName("默认预期状态：mcp_servers.yaml 是空清单时 connectAll 正常返回，一条 MCP 工具都不注册")
  void connectAll_emptyConfig_registersNothing() throws IOException {
    writeConfig("servers: []\n");
    AtomicInteger factoryCalls = new AtomicInteger();
    McpClientService service =
        new McpClientService(
            new McpServerConfigLoader(oryxosDir),
            config -> {
              factoryCalls.incrementAndGet();
              return clientReporting("never");
            });
    ToolRegistry registry = new ToolRegistry();

    assertThatCode(() -> service.connectAll(registry)).doesNotThrowAnyException();

    assertThat(registry.registered()).isEmpty();
    // 空清单下连客户端都不该造——造了就等于对空配置做了一次真实网络/进程动作
    assertThat(factoryCalls).hasValue(0);
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(McpClientService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) org.slf4j.LoggerFactory.getLogger(McpClientService.class)).detachAppender(appender);
  }

  /** 取一条日志事件里某个结构化字段的值；没有该字段时返回 {@code null}。 */
  private static String structuredValue(ILoggingEvent event, String key) {
    return event.getKeyValuePairs().stream()
        .filter(pair -> key.equals(pair.key))
        .map(pair -> String.valueOf(pair.value))
        .findFirst()
        .orElse(null);
  }
}
