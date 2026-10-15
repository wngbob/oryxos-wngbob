package io.oryxlabs.oryxos.tool.mcp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.ai.util.json.JsonParser;

/**
 * 把一个 MCP server 上报的工具包装成 {@link OryxTool}（contracts/mcp-integration.md §3.4）。
 *
 * <p>这是三档接入方式里门槛最低的那一档（方式一/二：复用社区 server 或自写 server）的落地——<b>业务方一行 Java 不写</b>， 只要在 {@code
 * mcp_servers.yaml} 里声明一个 server，它上报的每个工具都会变成与内置工具、注解工具身份完全相同的 {@code OryxTool}，ReAct 循环与 {@code
 * ToolRegistry} 都不感知来源差异（FR-001）。
 *
 * <p><b>入参 Schema 必须序列化回字符串，不能取 record 的 {@code toString()}</b>：{@code Tool.inputSchema()} 返回的是
 * {@code McpSchema.JsonSchema}（一个 record），它的 {@code toString()} 是 {@code JsonSchema[type=object,
 * properties={...}]} 这样的调试串——不是合法 JSON，Provider 拿它翻译 Function Calling 格式会直接卡住（FR-002）。这里走 SDK 自己的
 * {@code McpJsonDefaults.getMapper()} 序列化，顺带让本仓源码不必 import 任何 JSON 库，JSON 契约整个留在 SDK 内部。
 *
 * <p><b>{@code isError()} 是 {@code Boolean}，可能为 {@code null}</b>：协议里这个字段可选，server 不填就是 {@code
 * null}。 直接 {@code result.isError()} 拆箱、或 {@code !result.isError()} 取反，都会在 {@code null} 上抛
 * NPE——而一个「没填 isError 的成功响应」是完全正常的响应。判定必须走 {@code Boolean.TRUE.equals(...)}。
 *
 * <p><b>失败分两档</b>（contracts/mcp-integration.md §3.3）：{@code callTool} 抛异常说明是<b>运行中</b>挂的（连接期失联已在
 * {@link McpClientService} 里 WARN 跳过），值得重试；{@code isError()} 为真说明 server 听懂了但业务上失败了，重试同一个调用
 * 不会变好。分档口径与内置工具一致（FR-003）。
 *
 * <p><b>类是 {@code final} 的</b>：构造器里要序列化入参 Schema、可能上抛（见 {@link #serializeSchema}），SpotBugs 对「可被继承
 * 的类 + 会抛异常的构造器」报 CT_CONSTRUCTOR_THROW（怕子类在超类构造器抛异常后留下未初始化对象被 finalizer 碰）。本类没有、也
 * 不该有子类——一个工具适配器的身份完全由它包的那个 server 工具决定，改成 {@code final} 比加豁免更贴合事实。
 */
public final class McpToolAdapter implements OryxTool {

  private final McpSyncClient client;

  private final String name;

  private final String description;

  private final String inputSchema;

  /**
   * <b>关于 {@code @SuppressFBWarnings}</b>：{@code client} 是注入的协作对象，被 SpotBugs 判成
   * EI_EXPOSE_REP2（构造器把外部 可变对象直接存进字段）。这里没有第二种写法——{@code McpSyncClient} 是 SDK
   * 的连接句柄，既不可拷贝、也没有「值语义」的等价物， 它本来就必须按引用共享；且它是注入进来的依赖，不是本类对外暴露的内部状态。就地豁免并留下理由。
   *
   * @param client 已初始化、已连接该工具的宿主 server 的客户端
   * @param tool server 在 {@code listTools()} 里上报的工具描述
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "McpSyncClient 是注入的连接句柄、不可拷贝，必须按引用共享，属 SpotBugs 误报")
  public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool) {
    this.client = client;
    this.name = tool.name();
    this.description = tool.description();
    this.inputSchema = serializeSchema(tool);
  }

  /**
   * 把 SDK 的 {@code JsonSchema} record 序列化成合法 JSON 文本。
   *
   * <p>序列化不出来属于<b>编程/协议层面的异常</b>，不静默降级成空 Schema——空 Schema 会让工具在模型眼里「没有参数」， 调用必然构造错。这里上抛，由 {@link
   * McpClientService} 按「单个工具的问题只影响它自己」的口径 WARN + 跳过。
   */
  private static String serializeSchema(McpSchema.Tool tool) {
    try {
      return McpJsonDefaults.getMapper().writeValueAsString(tool.inputSchema());
    } catch (IOException e) {
      throw new IllegalStateException("无法序列化 MCP 工具入参 Schema: " + tool.name(), e);
    }
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getInputSchema() {
    return inputSchema;
  }

  /**
   * 把模型给的入参原样转发给 MCP server。
   *
   * <p><b>参数不做任何加工</b>：不补默认值、不删多余键、不改类型。schema 是 server 自己上报的，参数语义只有它清楚； 这里加工等于用本仓的猜测覆盖 server 的契约。
   *
   * <p>入参文本的解析用 {@code JsonParser}（与 {@link io.oryxlabs.oryxos.tool.registry.AnnotatedToolAdapter}
   * 同款）： contracts §4.6 禁止的是用别的库去解析 <b>MCP 协议自身的</b> JSON（schema / 结果报文），模型给的参数文本不属那一类。
   *
   * @param inputJson 模型给的入参 JSON；空串等同 {@code {}}
   * @return server 的返回内容包装成的结果
   */
  @Override
  public ToolResult execute(String inputJson) {
    Map<String, Object> arguments = parse(inputJson);

    McpSchema.CallToolResult result;
    try {
      result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
    } catch (RuntimeException e) {
      return ToolResult.retryableFailure("MCP 工具调用失败: " + e.getMessage());
    }

    String rendered = render(result);
    if (Boolean.TRUE.equals(result.isError())) {
      return ToolResult.failure(rendered);
    }
    return ToolResult.success(rendered);
  }

  /** 入参是模型给的文本，可能为空串（无参工具）——空串按空对象处理，不当作错误。 */
  private static Map<String, Object> parse(String inputJson) {
    if (inputJson == null || inputJson.isBlank()) {
      return Map.of();
    }
    // 走 requireNonNullElse 而不是三元判空：JSON 文本是字面量 "null" 时 fromJson 确实返回 null，但 SpotBugs 认定该返回值
    // 非空、把显式判空报成 RCN_REDUNDANT_NULLCHECK。语义一样，写法换成它不认得的那个
    return Objects.requireNonNullElse(JsonParser.fromJson(inputJson, Map.class), Map.of());
  }

  /**
   * 把 MCP 的内容块拼成一段文本。
   *
   * <p>文本块取 {@code text()}；其余内容类型（图片 / 音频 / 内嵌资源）没有纯文本形态，退回它的 {@code toString()}——
   * 丢信息也比丢结果好，模型至少知道「这里有一块非文本内容」。
   */
  private String render(McpSchema.CallToolResult result) {
    List<McpSchema.Content> contents = result.content();
    String text =
        contents == null
            ? ""
            : contents.stream()
                .map(
                    content ->
                        content instanceof McpSchema.TextContent textContent
                            ? textContent.text()
                            : String.valueOf(content))
                .collect(Collectors.joining("\n"));
    if (!text.isBlank()) {
      return text;
    }
    // 空内容不是错误：MCP 允许 server 用空的成功结果表示「做完了，没什么好说的」
    return Boolean.TRUE.equals(result.isError()) ? "MCP 工具 " + name + " 返回错误（服务端未给出错误详情）" : "（无内容）";
  }
}
