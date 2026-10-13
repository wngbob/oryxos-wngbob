package io.oryxlabs.oryxos.core.profile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个 Agent 的运行时配置（技术方案 §8.2「Profile」）。
 *
 * <p>Profile <b>不是</b>一份手写的独立 YAML：它由 Agent 目录 {@code .oryxos/agents/<name>/AGENT.md} 的 frontmatter
 * 派生（{@link ProfileLoader} 负责扫描与解析）。业务方只维护 Agent 目录，不维护 Profile 文件。
 *
 * <p>类本身建全字段，各 user story 按需取用——避免后续各节反复改这个类。
 *
 * <p><b>刻意不含</b> {@code skills} 与 {@code notify_channels}：Skill 绑定的唯一真相源是 Agent 目录内指向公共实体的相对软连接
 * （章程原则 IV），通知渠道归 SQLite 全局注册表（技术方案 §8.2），二者都不属于 frontmatter。
 *
 * @param name Agent 名（取自目录名，目录名即真相，不在 frontmatter 重复声明）
 * @param description 一句话说明这个 Agent 干什么
 * @param identity 人格 / 系统提示词，缺省为 {@link Identity#EMPTY}
 * @param provider 厂商选择（厂商名 / 模型 / 温度），由 {@link ProfileLoader} 校验后必有
 * @param tools 可用 Tool 子集；缺省为空
 * @param mcpServers 引用的 MCP Server 名；缺省为空
 * @param channels 入站渠道；缺省为空
 * @param schedules 定时任务（钟推定义源）；缺省为空
 * @param bootstrap 额外 Bootstrap 文件列表；缺省为空
 * @param settings 运行参数，缺省为 {@link Settings#DEFAULT}
 */
public record Profile(
    String name,
    String description,
    Identity identity,
    ProviderSpec provider,
    List<String> tools,
    List<String> mcpServers,
    List<ChannelSpec> channels,
    List<ScheduleSpec> schedules,
    List<String> bootstrap,
    Settings settings) {

  /** 未声明 {@code settings.max_iterations} 时的默认最大 ReAct 迭代次数。 */
  public static final int DEFAULT_MAX_ITERATIONS = 10;

  /** 未声明 {@code settings.max_history_turns} 时的默认对话历史截断轮数。 */
  public static final int DEFAULT_MAX_HISTORY_TURNS = 20;

  /** 归一化：可空集合收敛为不可变空表、可空子结构收敛为默认值，使下游不必处处判空。 */
  public Profile {
    identity = identity == null ? Identity.EMPTY : identity;
    settings = settings == null ? Settings.DEFAULT : settings;
    tools = tools == null ? List.of() : List.copyOf(tools);
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    channels = channels == null ? List.of() : List.copyOf(channels);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
  }

  /**
   * 身份段：人格名与系统提示词。
   *
   * @param agentName 人格名（展示用），可空
   * @param prompt 人格 / 系统提示词，可空（可改为引用 {@code SOUL.md}）
   */
  public record Identity(String agentName, String prompt) {

    /** 未声明 {@code identity} 段时的占位值。 */
    public static final Identity EMPTY = new Identity(null, null);
  }

  /**
   * 厂商选择段：决定这个 Agent 用哪家模型。
   *
   * <p>{@code name} 必须命中实例级厂商清单（{@code oryxos.providers}）中的同名项，否则该 Profile 判为不合法。
   *
   * @param name 厂商名，如 {@code deepseek}
   * @param model 模型名，如 {@code deepseek-chat}
   * @param temperature 采样温度；为 {@code null} 时不显式传给模型，用模型自身默认
   */
  public record ProviderSpec(String name, String model, Double temperature) {}

  /**
   * 入站渠道段：gateway 模式的挂载依据。
   *
   * @param name 渠道名
   * @param config 渠道自有配置
   */
  public record ChannelSpec(String name, Map<String, Object> config) {

    /** 归一化：{@code config} 收敛为不可变副本，避免调用方持有的原 Map 事后改动反过来影响 Profile。 */
    public ChannelSpec {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /**
   * 定时任务段：钟推的定义源（表 {@code scheduled_tasks} 只存运行状态）。
   *
   * @param cron cron 表达式
   * @param zone 时区
   * @param message 定时投递的消息
   */
  public record ScheduleSpec(String cron, String zone, String message) {}

  /**
   * 运行参数段。
   *
   * @param maxIterations 最大 ReAct 迭代次数，缺省 {@value Profile#DEFAULT_MAX_ITERATIONS}
   * @param maxHistoryTurns 最大对话历史轮数，缺省 {@value Profile#DEFAULT_MAX_HISTORY_TURNS}
   */
  public record Settings(Integer maxIterations, Integer maxHistoryTurns) {

    /** 未声明 {@code settings} 段时的默认值。 */
    public static final Settings DEFAULT =
        new Settings(DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_HISTORY_TURNS);

    /** 归一化：缺省项补默认值。 */
    public Settings {
      maxIterations = Objects.requireNonNullElse(maxIterations, DEFAULT_MAX_ITERATIONS);
      maxHistoryTurns = Objects.requireNonNullElse(maxHistoryTurns, DEFAULT_MAX_HISTORY_TURNS);
    }
  }
}
