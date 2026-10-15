package io.oryxlabs.oryxos.tool.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 读 {@code .oryxos/mcp_servers.yaml}，把声明转成 {@link McpServerConfig} 列表（contracts/mcp-integration.md
 * §1）。
 *
 * <p><b>路径口径只有一个</b>：构造参数就是工作区根（即 {@code .oryxos/} 目录本身，见 {@code Workspace#root()}），本类在它下面解析 {@code
 * mcp_servers.yaml}。装配处 MUST 传 {@code workspace.root()}——<b>MUST NOT 再叠一层 {@code .oryxos}</b>，
 * 那会解析成 {@code .oryxos/.oryxos/mcp_servers.yaml}，文件永远找不到，而「文件不存在 = 空清单」这条规则会把错误静默吞掉， 表现为「MCP
 * 永远连不上、且一条日志都没有」。这条由一条<b>真读到一个 server</b> 的正向用例钉死（只测异常分支测不出它）。
 *
 * <p><b>任何一条 server 的问题都只影响它自己</b>：MCP 是外部依赖，一个配错的 server 不该让 OryxOS 起不来。全部失败模式一律 WARN + 跳过该
 * server，绝不抛异常出本类（FR-024）。
 *
 * <p><b>凭证纪律</b>：{@code env} 里的 {@code ${ENV_VAR}} 在加载时解析；<b>解析出的值 MUST NOT 出现在任何日志、异常消息里</b>
 * （FR-027）。日志只能写「server X 的 GITHUB_TOKEN 未设置」，不能写它的值。本类因此从不打印 {@code env} 的任何一个值。
 *
 * <p><b>默认状态</b>：第 18 节的模板末尾是 {@code servers: []}（示例全在注释里），故默认配置下本类返回空清单、不连接任何 server——这是
 * <b>预期状态</b>，不是没做完（spec 仲裁 7）。
 */
public class McpServerConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(McpServerConfigLoader.class);

  /** 工作区内的固定文件名（第 18 节模板，本节一字不改）。 */
  private static final String CONFIG_FILE = "mcp_servers.yaml";

  /** 顶层键：server 列表。没有它视为空清单。 */
  private static final String SERVERS_KEY = "servers";

  /** 环境变量占位形如 {@code ${VAR_NAME}}，与 {@code ProfileLoader} 同款口径。 */
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private final Path oryxosDir;
  private final UnaryOperator<String> envLookup;

  /**
   * 生产构造：环境变量从进程环境读。
   *
   * @param oryxosDir 工作区根目录（{@code .oryxos/} 本身），本类在它下面解析 {@code mcp_servers.yaml}
   */
  public McpServerConfigLoader(Path oryxosDir) {
    this(oryxosDir, System::getenv);
  }

  /**
   * 供测试注入环境变量的构造。
   *
   * @param oryxosDir 工作区根目录
   * @param envLookup 环境变量查询函数；返回 {@code null} 表示未设置
   */
  McpServerConfigLoader(Path oryxosDir, UnaryOperator<String> envLookup) {
    this.oryxosDir = oryxosDir;
    this.envLookup = envLookup;
  }

  /**
   * 加载全部 server 声明。
   *
   * @return 合法的 server 清单（顺序 = 文件里的声明顺序）；文件缺失 / 为空 / 无 {@code servers} 键 / 语法错误时为空清单
   */
  public List<McpServerConfig> load() {
    Path file = oryxosDir.resolve(CONFIG_FILE);
    if (!Files.isRegularFile(file)) {
      // 未配置 MCP 是正常状态：不 WARN，否则每次启动都刷一条无意义的告警
      return List.of();
    }

    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.atWarn().addKeyValue("file", file).setCause(e).log("读取 MCP 配置失败，本次不连接任何 MCP server");
      return List.of();
    }

    Object parsed;
    try {
      // SafeConstructor：配置文件是外部文本，默认构造器允许 !! 标签实例化任意类
      parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    } catch (RuntimeException e) {
      log.atWarn().addKeyValue("file", file).setCause(e).log("MCP 配置不是合法 YAML，本次不连接任何 MCP server");
      return List.of();
    }

    if (!(parsed instanceof Map<?, ?> root)) {
      // 文件为空、只有注释、或顶层不是映射——一律视为空清单
      return List.of();
    }

    Object servers = root.get(SERVERS_KEY);
    if (!(servers instanceof List<?> entries)) {
      return List.of();
    }

    List<McpServerConfig> configs = new ArrayList<>(entries.size());
    Set<String> seenNames = new LinkedHashSet<>();
    for (Object entry : entries) {
      McpServerConfig config = toConfig(entry);
      if (config == null) {
        continue;
      }
      if (!seenNames.add(config.name())) {
        log.atWarn().addKeyValue("server", config.name()).log("MCP 配置里 server 名重复，保留先出现的那条、跳过本条");
        continue;
      }
      configs.add(config);
    }
    return List.copyOf(configs);
  }

  /** 单条声明的解析；任何问题都返回 {@code null} 让调用方跳过，不抛异常。 */
  private McpServerConfig toConfig(Object entry) {
    if (!(entry instanceof Map<?, ?> map)) {
      log.atWarn().addKeyValue("entry", entry).log("MCP 配置里有一条 server 声明不是键值映射，已跳过");
      return null;
    }

    String name = text(map.get("name"));
    if (name == null) {
      log.warn("MCP 配置里有一条 server 声明缺少 name，已跳过");
      return null;
    }

    String transport = text(map.get("transport"));
    if (!McpServerConfig.TRANSPORT_STDIO.equals(transport)
        && !McpServerConfig.TRANSPORT_SSE.equals(transport)) {
      log.atWarn()
          .addKeyValue("server", name)
          .addKeyValue("transport", transport)
          .log("MCP server 的 transport 必须是 stdio 或 sse，已跳过");
      return null;
    }

    List<String> command = stringList(map.get("command"));
    if (McpServerConfig.TRANSPORT_STDIO.equals(transport) && command.isEmpty()) {
      log.atWarn().addKeyValue("server", name).log("MCP server 是 stdio 传输但缺少 command，已跳过");
      return null;
    }

    String url = text(map.get("url"));
    if (McpServerConfig.TRANSPORT_SSE.equals(transport) && url == null) {
      log.atWarn().addKeyValue("server", name).log("MCP server 是 sse 传输但缺少 url，已跳过");
      return null;
    }

    Map<String, String> env = resolveEnv(name, map.get("env"));
    if (env == null) {
      return null;
    }

    return new McpServerConfig(name, transport, command, env, url);
  }

  /**
   * 解析 {@code env} 里的 {@code ${ENV_VAR}} 占位。
   *
   * <p><b>解析不到就跳过整个 server，而不是留下字面量</b>：把 {@code "${GITHUB_TOKEN}"} 原样交给子进程，等于让 server 拿着一个
   * 假凭证去连——失败现场在子进程里，排查成本远高于在这里报一句「哪个变量没设置」。也 MUST NOT 猜测或补默认值。
   *
   * @return 解析后的 env；有变量解析不到时返回 {@code null}
   */
  private Map<String, String> resolveEnv(String serverName, Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, String> resolved = new LinkedHashMap<>(map.size());
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      String key = String.valueOf(entry.getKey());
      String value = text(entry.getValue());
      if (value == null) {
        resolved.put(key, "");
        continue;
      }
      Matcher matcher = ENV_PLACEHOLDER.matcher(value);
      StringBuilder expanded = new StringBuilder();
      while (matcher.find()) {
        String varName = matcher.group(1);
        String replacement = envLookup.apply(varName);
        if (replacement == null) {
          // 只记变量名，MUST NOT 记它的值（可能压根没有值，也可能有值但不可泄漏）
          log.atWarn()
              .addKeyValue("server", serverName)
              .addKeyValue("envVar", varName)
              .log("MCP server 的环境变量未设置，已跳过该 server");
          return null;
        }
        matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
      }
      matcher.appendTail(expanded);
      resolved.put(key, expanded.toString());
    }
    return Map.copyOf(resolved);
  }

  /** 非字符串或空白一律归一成 {@code null}，让「有没有写」这件事只有一个判据。 */
  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : text;
  }

  /** {@code command} 逐元素转字符串；不是列表时返回空表（由调用方判「stdio 缺 command」）。 */
  private static List<String> stringList(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>(list.size());
    for (Object item : list) {
      String text = text(item);
      if (text == null) {
        // 元素为空说明这一行 argv 写坏了，整个 server 都不可信
        return List.of();
      }
      result.add(text);
    }
    return List.copyOf(result);
  }
}
