package io.oryxlabs.oryxos.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个 MCP server 的声明（contracts/mcp-integration.md §1.2）。键名与 {@code .oryxos/mcp_servers.yaml}
 * 的模板一一对应。
 *
 * <p><b>键名冻结</b>：模板由第 18 节交付，本节 MUST NOT 改它的结构与键名（软门禁 ②）。本 record 只是把模板里的键读成类型安全的形状，
 * 不新增、不改名、不给默认值——配置里没写的东西，这里就该是 {@code null}，让加载器去判「缺了必填项」而不是由本类悄悄补一个值。
 *
 * <p><b>{@code command} 是 {@code List<String>} 而不是一条命令行字符串</b>：它逐元素作为 argv 传给 {@code
 * ProcessBuilder}，不经 Shell 解释。写成字符串就等于把「怎么切分」变成一处需要猜测的语义。
 *
 * @param name server 名；用于来源标签 {@code mcp:<name>}，MUST 在文件内唯一
 * @param transport 传输方式：{@code stdio} 或 {@code sse}
 * @param command 可执行文件 + 参数；{@code transport: stdio} 时必填
 * @param env 环境变量；值支持 {@code ${ENV_VAR}} 占位，{@code transport: stdio} 时使用
 * @param url SSE 端点；{@code transport: sse} 时必填
 */
public record McpServerConfig(
    String name, String transport, List<String> command, Map<String, String> env, String url) {

  /** {@code transport} 的合法取值。取值不在其中 → 加载器 WARN + 跳过该 server（不抛异常）。 */
  public static final String TRANSPORT_STDIO = "stdio";

  /** {@code transport} 的合法取值。 */
  public static final String TRANSPORT_SSE = "sse";

  /**
   * 两个集合分量做防御性拷贝，让这个 record 真的不可变（否则 {@code command()} / {@code env()} 会把内部列表交出去， SpotBugs 报
   * EI_EXPOSE_REP / EI_EXPOSE_REP2）。
   *
   * <p><b>这不违反上面那条「不给默认值」</b>：那条针对的是 {@code name} / {@code transport} / {@code url}——加载器要靠它们为
   * {@code null} 来判断「缺了必填项」。{@code command} / {@code env} 是集合，加载器本就把「没写」归一成空集合再传进来；这里对 {@code null}
   * 的容忍只是防御，不改变任何既有调用方的观测结果。
   */
  public McpServerConfig {
    command = command == null ? List.of() : List.copyOf(command);
    env = env == null ? Map.of() : Map.copyOf(env);
  }
}
