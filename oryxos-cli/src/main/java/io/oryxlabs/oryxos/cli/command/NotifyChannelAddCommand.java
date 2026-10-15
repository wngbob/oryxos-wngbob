package io.oryxlabs.oryxos.cli.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code oryxos notify-channel add <name> --type <type> (--url <值> | --url-env <ENV_VAR>)}：注册一个通知渠道
 * （contracts/cli-surface.md §2.12）。
 *
 * <p><b>凭证不进库、也不进 shell 历史</b>：{@code --url-env FEISHU_HOOK_URL} 只是 {@code --url
 * '${FEISHU_HOOK_URL}'} 的糖，入库的是<b>字面量占位符</b>，真值由读路径 {@code JpaNotifyChannelRegistry} 在解析渠道时从环境变量取
 * （合 TechnicalSolution §6.8「在发送时从环境变量解析，不明文落库」）。{@code --url} 也收字面量地址，但那样 token 会同时落库、进 shell
 * 历史——所以走这条路时输出里给一句提示，且<b>绝不回显那个值</b>。
 *
 * <p><b>输出纪律（硬约束，测试钉死）</b>：{@code url} 含 token，任何输出（含异常消息）MUST NOT 出现它的值。 用 {@code --url-env}
 * 时可以打印<b>占位符本身</b>——{@code ${FEISHU_HOOK_URL}} 是变量名，不是凭证。
 *
 * <p><b>重名不覆盖</b>：用 {@code INSERT OR IGNORE} 一条语句判，而不是「先 SELECT 再 INSERT」——后者两次往返之间有窗口，
 * 且要多一条查询语句。{@code executeUpdate()} 返回 0 就说明这个名字已被占用，报错并提示先 {@code remove}。
 */
@Command(
    name = "add",
    mixinStandardHelpOptions = true,
    description = "注册一个通知渠道（重名不覆盖；凭证建议用 --url-env 走环境变量）")
public final class NotifyChannelAddCommand implements Runnable {

  /**
   * {@code --config} 里 MUST NOT 出现的键。
   *
   * <p>读路径会往 config 里强塞这个键（{@code JpaNotifyChannelRegistry.URL_KEY}），从写入侧也塞会造出第二个真相源—— 地址到底以列为准还是以
   * config 为准，届时没有人说得清。
   */
  private static final String URL_KEY = "url";

  /** 环境变量名形状；与读路径的占位符正则（{@code ENV_PLACEHOLDER}）同一套字符集，否则写进去的占位符永远匹配不上。 */
  private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** 值全部走 {@code ?} 绑定。{@code OR IGNORE}：名字已占用时不报错、返回受影响行数 0，由调用方翻成中文提示。 */
  private static final String INSERT_SQL =
      "INSERT OR IGNORE INTO notify_channels (name, type, url, config_json, description)"
          + " VALUES (?, ?, ?, ?, ?)";

  /** 只用来把 {@code --config} 的键值对写成 JSON；不手写转义（那是必然写错一处的那类代码）。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Workspace workspace;

  @Parameters(index = "0", paramLabel = "<name>", description = "渠道注册名（Agent 与模型按它引用），如 team-lark")
  private String name;

  @Option(
      names = "--type",
      required = true,
      paramLabel = "<type>",
      description = "渠道类型，如 webhook / feishu / lark / wecom（取值须与底座装配的路由表一致）")
  private String type;

  @Option(
      names = "--url",
      paramLabel = "<值>",
      description = "webhook 地址，原样入库；写成 ${VAR} 则由发送端从环境变量解析")
  private String url;

  @Option(
      names = "--url-env",
      paramLabel = "<ENV_VAR>",
      description = "地址所在的环境变量名（推荐：凭证不落库、不进 shell 历史）")
  private String urlEnv;

  @Option(names = "--description", paramLabel = "<text>", description = "人类可读的一句话说明")
  private String description;

  @Option(
      names = "--config",
      paramLabel = "<k=v>",
      description = "类型相关配置（如 secret=${FEISHU_SECRET}），可重复；值里的 = 不影响切分")
  private List<String> config = new ArrayList<>();

  /**
   * @param workspace 工作区；库文件取它的 {@code dbFile()}
   */
  public NotifyChannelAddCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    String channelName = requireUsableName(name);
    String channelType = requireNonBlank(type, "--type");
    String address = resolveAddress(channelName);
    Map<String, String> configMap = parseConfig();
    String viaEnv = urlEnv == null || urlEnv.isBlank() ? null : urlEnv.trim();

    try (WorkspaceDb database = NotifyChannelDb.open(workspace)) {
      int inserted =
          database.execute(
              INSERT_SQL,
              channelName,
              channelType,
              address,
              toJson(configMap),
              blankToNull(description));
      if (inserted == 0) {
        throw new IllegalStateException(
            "通知渠道 '"
                + channelName
                + "' 已存在，不覆盖；如需重建请先 `oryxos notify-channel remove "
                + channelName
                + "`");
      }
    }
    report(channelName, channelType, viaEnv);
  }

  /** 渠道名去空白后必须非空、且不含空白——它是主键，也是 Agent 与模型引用它的唯一凭据。 */
  private static String requireUsableName(String raw) {
    String channelName = raw == null ? "" : raw.trim();
    if (channelName.isEmpty()) {
      throw new IllegalStateException(
          "请提供渠道名：`oryxos notify-channel add <name> --type <type> --url-env <ENV_VAR>`");
    }
    if (channelName.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalStateException("渠道名不能含空白：" + channelName);
    }
    return channelName;
  }

  private static String requireNonBlank(String raw, String option) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) {
      throw new IllegalStateException(option + " 不能为空");
    }
    return value;
  }

  /**
   * 把两个地址选项收敛成一个入库值。
   *
   * <p>{@code --url-env} 的名字<b>必须</b>校验形状：写成 {@code --url-env 'FOO BAR'} 会存下 {@code ${FOO BAR}}，
   * 而读路径的占位符正则匹配不到它——占位符会被当成字面地址原样发给对端，<b>静默失败</b>。这里拦住比事后排查便宜得多。
   */
  private String resolveAddress(String channelName) {
    boolean hasUrl = url != null && !url.isBlank();
    boolean hasUrlEnv = urlEnv != null && !urlEnv.isBlank();
    if (hasUrl && hasUrlEnv) {
      throw new IllegalStateException("--url 与 --url-env 只能给一个（--url-env 就是 --url '${VAR}' 的糖）");
    }
    if (!hasUrl && !hasUrlEnv) {
      throw new IllegalStateException(
          "请用 --url-env <ENV_VAR>（推荐，凭证不落库）或 --url <值> 指定 webhook 地址："
              + "`oryxos notify-channel add "
              + channelName
              + " --type <type> --url-env FEISHU_HOOK_URL`");
    }
    if (hasUrlEnv) {
      String varName = urlEnv.trim();
      if (!ENV_VAR_NAME.matcher(varName).matches()) {
        throw new IllegalStateException("--url-env 要的是环境变量名（字母或下划线开头，只含字母/数字/下划线），收到：" + varName);
      }
      return "${" + varName + "}";
    }
    return url.trim();
  }

  /**
   * 把 {@code --config k=v} 解析成有序键值对。
   *
   * <p><b>只在第一个 {@code =} 处切分</b>：base64 的 secret 常以 {@code =} 结尾，按全部 {@code =} 切会把它切坏。
   *
   * <p><b>报错不回显值</b>：键名不是凭证、可以打；值可能是凭证，一律不出现在任何消息里。
   */
  private Map<String, String> parseConfig() {
    Map<String, String> parsed = new LinkedHashMap<>();
    for (String entry : config) {
      int separator = entry.indexOf('=');
      if (separator < 0) {
        throw new IllegalStateException("--config 的每一项都要写成 k=v 形式；有一项没有 '='");
      }
      String key = entry.substring(0, separator).trim();
      if (key.isEmpty()) {
        throw new IllegalStateException("--config 的键不能为空");
      }
      if (URL_KEY.equals(key)) {
        throw new IllegalStateException(
            "--config 里不能写 url=...：地址请用 --url 或 --url-env 指定，不要写进 --config");
      }
      if (parsed.containsKey(key)) {
        throw new IllegalStateException("--config 的键 '" + key + "' 出现了两次");
      }
      parsed.put(key, entry.substring(separator + 1));
    }
    return parsed;
  }

  /** 没配就存 {@code NULL}——读路径把 {@code null} / 空白当空表，与存 {@code "{}"} 等价，但行更干净。 */
  private static String toJson(Map<String, String> configMap) {
    if (configMap.isEmpty()) {
      return null;
    }
    try {
      return JSON.writeValueAsString(configMap);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化 --config 失败：" + e.getMessage(), e);
    }
  }

  private static String blankToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * 只打渠道名、类型，以及「地址从哪来」——{@code url} 的<b>值</b>一个字符都不出现。
   *
   * <p>走 {@code --url-env} 时打的是<b>变量名</b>（{@code ${FEISHU_HOOK_URL}}）：它是用户下一步要设的东西，
   * 不说就等于让人去猜；而变量名不是凭证。
   *
   * @param viaEnv 环境变量名；地址是字面量时为 {@code null}
   */
  private static void report(String channelName, String channelType, String viaEnv) {
    System.out.println("已添加通知渠道 '" + channelName + "'（type=" + channelType + "）");
    if (viaEnv != null) {
      System.out.println("  地址：${" + viaEnv + "}——发送时从环境变量解析，凭证未落库");
    } else {
      System.out.println(
          "  提示：地址已按字面量入库。含 token 的地址建议改用 --url-env <ENV_VAR>，" + "凭证不落库、也不进 shell 历史");
    }
  }
}
