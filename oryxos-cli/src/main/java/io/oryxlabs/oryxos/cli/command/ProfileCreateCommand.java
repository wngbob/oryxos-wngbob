package io.oryxlabs.oryxos.cli.command;

import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * {@code oryxos profile create <name>}：生成一个最小可用的 Agent 定义（FR-011、contracts/cli-surface.md §2.6）。
 *
 * <p><b>Agent 名就是目录名</b>：建出来的是 {@code .oryxos/agents/<name>/AGENT.md}，不额外写一份 Profile 文件 ——Profile 由
 * {@code ProfileLoader} 从 frontmatter 派生，手写第二份必然与目录名漂移。
 *
 * <p><b>模板必须一建出来就是有效 Agent</b>：{@code profile create weather} 之后立刻 {@code profile list} 就该看到
 * 「有效」，否则用户会以为是自己写错了。所以厂商名取自 {@link ProviderProperties#SUPPORTED_PROVIDERS}， 而不是在 CLI
 * 侧写死一个字面量（写死的话，厂商集合一变模板就失效）。
 */
@Command(
    name = "create",
    mixinStandardHelpOptions = true,
    description = "创建一个 Agent（生成 .oryxos/agents/<name>/AGENT.md 模板；已存在则报错不覆盖）")
public final class ProfileCreateCommand implements Runnable {

  /**
   * 模板预填的厂商名：底座支持集合里的有序首项。
   *
   * <p>从集合派生而不是写死，是为了让「模板的 {@code provider.name} 必在 {@code SUPPORTED_PROVIDERS} 内」这条
   * 由构造保证，而不是靠人去核对。{@code sorted()} 不能省——{@code Set.of} 的迭代顺序没有约定。
   */
  private static final String TEMPLATE_PROVIDER =
      ProviderProperties.SUPPORTED_PROVIDERS.stream()
          .sorted()
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("底座没有任何已实现的厂商，无法生成 Agent 模板"));

  /**
   * 模板预填的模型名。
   *
   * <p>与厂商名是两个独立的真相源（厂商集合在 {@code oryxos-provider}、可用模型名在那家厂商的 SDK 里），
   * 这里只钉一个能跑通的缺省值，模板注释里写明要按实际需要改。
   */
  private static final String TEMPLATE_MODEL = "deepseek-chat";

  /** Agent 名里不允许出现的字符：路径分隔符（会建到 agents/ 之外）、{@code :}（会话标识的分隔符）、{@code "}（模板里要引起来）。 */
  private static final String FORBIDDEN_IN_NAME = "/\\:\"";

  private final Workspace workspace;

  @Parameters(index = "0", paramLabel = "<name>", description = "Agent 名，同时是目录名，如 weather")
  private String name;

  /**
   * @param workspace 工作区；Agent 目录取它的 {@code agentsDir()}
   */
  public ProfileCreateCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    workspace.requireExists();
    String agentName = name == null ? "" : name.trim();
    requireUsableName(agentName);
    Path agentDir = workspace.agentsDir().resolve(agentName);
    if (Files.exists(agentDir)) {
      throw new IllegalStateException(
          "Agent '"
              + agentName
              + "' 已存在（"
              + agentDir
              + "），不覆盖；如需重建请先 `oryxos profile delete "
              + agentName
              + "`");
    }
    Path agentFile = agentDir.resolve("AGENT.md");
    try {
      Files.createDirectories(agentDir);
      Files.writeString(agentFile, template(agentName), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("创建 Agent '" + agentName + "' 失败：" + e.getMessage(), e);
    }
    System.out.println("已创建 Agent '" + agentName + "'：" + agentFile);
    System.out.println("请编辑它：填好 description 与 identity.prompt，按需声明 tools / mcp_servers");
  }

  private static void requireUsableName(String agentName) {
    if (agentName.isEmpty()) {
      throw new IllegalStateException("请提供 Agent 名：`oryxos profile create <name>`");
    }
    for (char forbidden : FORBIDDEN_IN_NAME.toCharArray()) {
      if (agentName.indexOf(forbidden) >= 0) {
        throw new IllegalStateException(
            "Agent 名不能含 '" + forbidden + "'：" + agentName + "（目录名即 Agent 名，且会进会话标识）");
      }
    }
    if (agentName.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalStateException("Agent 名不能含空白：" + agentName);
    }
  }

  /**
   * 生成 AGENT.md 模板。
   *
   * <p><b>用占位符替换而不是 {@code String.formatted}</b>：文本块里的换行是真实换行符，一旦这个字符串被当成格式串， 静态门禁就会要求把每个换行改写成
   * {@code %n}（{@code VA_FORMAT_STRING_USES_NEWLINE}）——那会把一份给人读的模板 改成一堆转义。占位符替换没有这个问题，模板正文也能原样保留。
   */
  private static String template(String agentName) {
    return """
        ---
        description: "一句话说明这个 Agent 干什么"
        identity:
          agent_name: "{{AGENT_NAME}}"
          prompt: "你是一个务实、直接的助手。先给结论，再给依据。"
        provider:
          name: "{{PROVIDER}}"
          model: "{{MODEL}}"
        tools: []
        mcp_servers: []
        settings:
          max_iterations: 10
          max_history_turns: 20
        ---

        <!-- 两个 --- 之间是 frontmatter：运行配置，改这里就是改这个 Agent 的行为。 -->
        <!-- 下面是正文：这个 Agent 的任务指令，会作为系统提示词的一部分注入。 -->

        ## 你负责什么

        （写清楚：什么时候会被叫起来、要产出什么、产出成什么形状。）

        ## 你可以怎么做

        （写清楚：可以用哪些工具、拿不准的时候怎么办。）
        """
        .replace("{{AGENT_NAME}}", agentName)
        .replace("{{PROVIDER}}", TEMPLATE_PROVIDER)
        .replace("{{MODEL}}", TEMPLATE_MODEL);
  }
}
