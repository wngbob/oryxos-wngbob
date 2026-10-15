package io.oryxlabs.oryxos.tool.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link McpToolAdapter} 的两条实测形状约束（contracts/mcp-integration.md §3.4）与参数转发保真。
 *
 * <p><b>为什么用替身客户端而不是真 server</b>：本测试要判的是「包装层有没有把 SDK 给的东西原样带出去」，与被测 server 毫无关系。 起一个真 MCP server
 * 会把「包装层对不对」和「server 通不通」两件事搅在一起，且慢、且依赖外部环境。{@code McpSyncClient} 的构造器是 包级私有的（不可子类化），故用 Mockito
 * 造替身——它走 Objenesis 不调构造器。
 */
class McpToolAdapterTest {

  /** SDK 的 mapper 只对 {@code TypeRef} 保泛型，反序列化成 Map 需要它。 */
  private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};

  private static final McpSchema.Tool WEATHER_TOOL =
      McpSchema.Tool.builder()
          .name("city_weather")
          .description("查某个城市的当前天气")
          .inputSchema(
              new McpSchema.JsonSchema(
                  "object",
                  Map.of("city", Map.of("type", "string")),
                  List.of("city"),
                  null,
                  null,
                  null))
          .build();

  private static McpSyncClient clientReturning(McpSchema.CallToolResult result) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(result);
    return client;
  }

  @Test
  @DisplayName("入参 Schema 是合法 JSON：SDK 的 mapper 能反序列化回来（不是 record 的 toString() 调试串）")
  void inputSchema_isValidJson() throws IOException {
    McpToolAdapter adapter = new McpToolAdapter(mock(McpSyncClient.class), WEATHER_TOOL);

    String schema = adapter.getInputSchema();
    Map<String, Object> parsed = McpJsonDefaults.getMapper().readValue(schema, MAP_TYPE);

    assertThat(parsed).containsEntry("type", "object");
    assertThat(parsed).containsKey("properties");
    assertThat(String.valueOf(parsed.get("required"))).contains("city");
    // 对照：JsonSchema 的 toString() 长这样，它不是 JSON——上面能反序列化成功，正说明走的不是它
    assertThat(schema).doesNotContain("JsonSchema[").doesNotContain("properties={");
  }

  @Test
  @DisplayName("元数据来自 server 上报：名字与用途说明原样带出")
  void metadata_comesFromServerReportedTool() {
    McpToolAdapter adapter = new McpToolAdapter(mock(McpSyncClient.class), WEATHER_TOOL);

    assertThat(adapter.getName()).isEqualTo("city_weather");
    assertThat(adapter.getDescription()).isEqualTo("查某个城市的当前天气");
  }

  @Test
  @DisplayName("isError() 为 null 时不 NPE：按成功处理（协议里该字段可选，不填是正常响应）")
  void callTool_isErrorNull_isTreatedAsSuccess() {
    McpToolAdapter adapter =
        new McpToolAdapter(
            clientReturning(new McpSchema.CallToolResult("晴天 25 度", null)), WEATHER_TOOL);

    ToolResult result = adapter.execute("{\"city\":\"杭州\"}");

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("晴天 25 度");
  }

  @Test
  @DisplayName("isError() 为 true → 不可重试失败（server 听懂了但业务上失败，重试同一个调用不会变好）")
  void callTool_isErrorTrue_isNotRetryable() {
    McpToolAdapter adapter =
        new McpToolAdapter(
            clientReturning(new McpSchema.CallToolResult("城市不存在", Boolean.TRUE)), WEATHER_TOOL);

    ToolResult result = adapter.execute("{\"city\":\"不存在的城市\"}");

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("城市不存在");
  }

  @Test
  @DisplayName("调用时参数原样转发：键值一字不改地进 CallToolRequest，工具名用 server 上报的那个")
  void callTool_forwardsArgumentsVerbatim() {
    McpSyncClient client = clientReturning(new McpSchema.CallToolResult("ok", null));
    ArgumentCaptor<McpSchema.CallToolRequest> captor =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);

    new McpToolAdapter(client, WEATHER_TOOL).execute("{\"city\":\"杭州\",\"days\":3}");

    verify(client).callTool(captor.capture());
    assertThat(captor.getValue().name()).isEqualTo("city_weather");
    assertThat(captor.getValue().arguments())
        .hasSize(2)
        .containsEntry("city", "杭州")
        .containsEntry("days", 3);
  }

  @Test
  @DisplayName("server 运行中挂掉（callTool 抛异常）→ 可重试失败，异常不抛给 ReAct 循环")
  void callTool_throws_isRetryableFailure() {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.callTool(any(McpSchema.CallToolRequest.class)))
        .thenThrow(new IllegalStateException("连接已断开"));

    ToolResult result = new McpToolAdapter(client, WEATHER_TOOL).execute("{}");

    assertThat(result.success()).isFalse();
    assertThat(result.retryable()).isTrue();
    assertThat(result.errorMessage()).contains("连接已断开");
  }
}
