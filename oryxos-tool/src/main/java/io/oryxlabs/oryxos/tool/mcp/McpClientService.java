package io.oryxlabs.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动时连接全部已声明的 MCP server、拉取它们的工具、包装注册进 {@link ToolRegistry} （contracts/mcp-integration.md
 * §3；FR-009）。
 *
 * <p><b>失联隔离是本类的全部价值</b>（FR-010 / SC-004）：外部 server 连不上<b>不能拖垮 OryxOS 自己的启动</b>。连接、初始化、
 * 列举三个阶段<b>任一失败都只 WARN + 跳过该 server</b>，异常 MUST NOT 逃出 {@link #connectAll}——一个配错的 server 让 每次
 * {@code chat} / {@code serve} 启动都挂死，是「外部依赖拖垮自身」最典型的形态。
 *
 * <p><b>有界等待</b>：{@code requestTimeout} 与 {@code initializationTimeout} 都钉在 {@value
 * #TIMEOUT_SECONDS} 秒，否则一个 地址配错、但 TCP 层能连上的 server（例如连到一个不说话的端口）会让启动无限期挂住。
 *
 * <p><b>凭证纪律</b>（FR-027）：WARN 只带 server 名与失败原因，{@code env} 里的任何值都不进日志。{@code mcp_servers.yaml} 里的
 * {@code ${ENV_VAR}} 在 {@link McpServerConfigLoader} 里就已解析成明文并交给子进程，本类只是它的搬运工。
 *
 * <p><b>默认不连任何 server</b>：第 18 节模板末尾是 {@code servers: []}，故本节交付后默认状态就是「一条 MCP 日志都没有」——
 * 这是<b>预期状态</b>，不是没做完（spec 仲裁 7）。
 */
public class McpClientService {

  private static final Logger log = LoggerFactory.getLogger(McpClientService.class);

  /** 连接 + 初始化 + 列举的合计上限（contracts/mcp-integration.md §3.2，本规约新增字面量）。 */
  private static final int TIMEOUT_SECONDS = 10;

  private final McpServerConfigLoader configLoader;

  private final Function<McpServerConfig, McpSyncClient> clientFactory;

  /**
   * 生产构造：客户端按真实传输（stdio / sse）创建。
   *
   * @param configLoader MCP 配置加载器
   */
  public McpClientService(McpServerConfigLoader configLoader) {
    this(configLoader, McpClientService::connect);
  }

  /**
   * 包级私有：单测从这里注入替身客户端，把「连接期失联」变成可控的几毫秒——生产调用方够不着，不构成对外概念。
   *
   * <p>与第 19 节 {@code WebhookNotifyAdapter} 的包级私有构造同款惯例：<b>测试够得着、生产够不着、不新增任何 public 概念</b>。
   * 没有这条接缝，「某个 server 失联不拖垮启动」这条回归只能靠起一个真 server 来验，那是集成测试的形态，不是单测。
   *
   * @param configLoader MCP 配置加载器
   * @param clientFactory 由 {@link McpServerConfig} 造客户端；测试注入替身
   */
  McpClientService(
      McpServerConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory) {
    this.configLoader = configLoader;
    this.clientFactory = clientFactory;
  }

  /**
   * 连接全部已声明的 server，把各自的工具注册进注册表。
   *
   * <p><b>MCP 工具最后注册</b>：调用方（{@code CliAssemblyConfiguration.toolRegistry}）在注册完内置工具与注解 Bean
   * 之后才调本方法， 于是工具重名时保留的是先注册的那一方（内置），MCP 的那个被丢弃 + WARN（FR-023 / contracts §3.5）。
   *
   * @param registry 目标注册表
   */
  public void connectAll(ToolRegistry registry) {
    List<McpServerConfig> configs = configLoader.load();
    for (McpServerConfig config : configs) {
      connectOne(registry, config);
    }
  }

  /** 单条 server 的连接与注册；任何阶段的失败都只影响它自己。 */
  private void connectOne(ToolRegistry registry, McpServerConfig config) {
    String serverName = config.name();

    McpSyncClient client;
    try {
      client = clientFactory.apply(config);
      client.initialize();
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("server", serverName)
          .addKeyValue("reason", e.getMessage())
          .log("MCP server 连接或初始化失败，跳过它的工具");
      return;
    }

    List<McpSchema.Tool> tools;
    try {
      tools = client.listTools().tools();
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("server", serverName)
          .addKeyValue("reason", e.getMessage())
          .log("MCP server 列举工具失败，跳过它的工具");
      return;
    }

    if (tools == null || tools.isEmpty()) {
      // 连上了、握手也成功了，只是这个 server 一个工具都没有——不算失败，不打 WARN
      return;
    }

    for (McpSchema.Tool tool : tools) {
      try {
        registry.register(new McpToolAdapter(client, tool), "mcp:" + serverName);
      } catch (RuntimeException e) {
        // 粒度到单个工具：一个工具包装不了（如 Schema 序列化失败）不该带走同 server 的其它工具
        log.atWarn()
            .addKeyValue("server", serverName)
            .addKeyValue("tool", tool.name())
            .addKeyValue("reason", e.getMessage())
            .log("MCP server 的工具包装失败，跳过该工具");
      }
    }
  }

  /**
   * 按配置里的 {@code transport} 造一个已建好、尚未握手的同步客户端。
   *
   * <p>两种传输的构造方式不同：{@code stdio} 要显式给 mapper（{@code StdioClientTransport} 的公开构造器要求）， {@code sse}
   * 自己从 {@code McpJsonDefaults} 取。{@code transport} 的合法性已由 {@link McpServerConfigLoader} 保证，
   * 这里只可能收到 {@code stdio} 或 {@code sse}。
   */
  static McpSyncClient connect(McpServerConfig config) {
    McpClientTransport transport;
    if (McpServerConfig.TRANSPORT_SSE.equals(config.transport())) {
      transport = HttpClientSseClientTransport.builder(config.url()).build();
    } else {
      transport = new StdioClientTransport(toServerParameters(config), McpJsonDefaults.getMapper());
    }
    return McpClient.sync(transport)
        .requestTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .initializationTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .build();
  }

  /** {@code command} 的第 0 个元素是可执行文件，其余逐个作为 argv——不经 Shell 解释（与 {@code ShellTools} 同款口径）。 */
  private static ServerParameters toServerParameters(McpServerConfig config) {
    List<String> command = config.command();
    ServerParameters.Builder builder = ServerParameters.builder(command.get(0));
    if (command.size() > 1) {
      builder.args(command.subList(1, command.size()));
    }
    if (!config.env().isEmpty()) {
      builder.env(config.env());
    }
    return builder.build();
  }
}
