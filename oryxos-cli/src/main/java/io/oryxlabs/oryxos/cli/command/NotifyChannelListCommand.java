package io.oryxlabs.oryxos.cli.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.cli.workspace.Workspace;
import io.oryxlabs.oryxos.cli.workspace.WorkspaceDb;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;

/**
 * {@code oryxos notify-channel list}：列出通知渠道注册表（contracts/cli-surface.md §2.12）。
 *
 * <p><b>输出纪律（硬约束，测试钉死）</b>：{@code url} 列含 token。
 *
 * <ul>
 *   <li>{@code url} 列：整串<b>恰好</b>是一个 {@code ${VAR}} 占位 → 原样打印（用户得知道该设哪个环境变量，变量名不是凭证）； 其余一律打 {@code
 *       <已配置>}——哪怕是看不出是不是凭证的字面量，也按凭证对待。
 *   <li>{@code config} 列：<b>只打键名</b>，从不打值。{@code secret} 这类键的值就是密钥。
 * </ul>
 *
 * <p><b>按 {@code name} 升序</b>：与读路径的默认渠道口径同序（{@code findFirstByOrderByNameAsc} 下推成 {@code ORDER BY
 * name ASC LIMIT 1}），因此<b>第一行就是默认渠道</b>——不指定渠道名时 {@code notify} 推的就是它。表头下标注这一点。
 */
@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "列出通知渠道（注册名 / 类型 / 地址来源 / 配置键 / 说明），按注册名升序，首行为默认渠道")
public final class NotifyChannelListCommand implements Runnable {

  /** 五列表头。 */
  private static final String HEADER = "name  type  url  config  description";

  private static final String ABSENT = "—";

  /** 字面量地址在输出里的替身。 */
  private static final String CONFIGURED = "<已配置>";

  /** {@code config_json} 解析不出来时的替身（写入面保证了形状，但表是人工也可写的）。 */
  private static final String UNPARSABLE = "<无法解析>";

  /** 整串恰好是一个占位符时才算「地址来自环境变量」；混排（{@code ${H}/path}）按字面量对待。 */
  private static final Pattern PURE_PLACEHOLDER =
      Pattern.compile("^\\$\\{[A-Za-z_][A-Za-z0-9_]*}$");

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SQL =
      "SELECT name, type, url, config_json, description FROM notify_channels ORDER BY name ASC";

  private final Workspace workspace;

  /**
   * @param workspace 工作区；库文件取它的 {@code dbFile()}
   */
  public NotifyChannelListCommand(Workspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public void run() {
    List<Row> rows;
    try (WorkspaceDb database = NotifyChannelDb.open(workspace)) {
      rows = database.query(SQL, NotifyChannelListCommand::map);
    }
    report(rows);
  }

  private static Row map(ResultSet resultSet) throws SQLException {
    return new Row(
        resultSet.getString("name"),
        resultSet.getString("type"),
        resultSet.getString("url"),
        resultSet.getString("config_json"),
        resultSet.getString("description"));
  }

  private static void report(List<Row> rows) {
    System.out.println("通知渠道（共 " + rows.size() + " 条，按注册名升序，首行为默认渠道）:");
    if (rows.isEmpty()) {
      System.out.println(
          "  （无：用 `oryxos notify-channel add <name> --type <type> --url-env <ENV_VAR>` 添加一个）");
      return;
    }
    System.out.println("  " + HEADER);
    for (Row row : rows) {
      System.out.println(
          "  "
              + render(row.name())
              + "  "
              + render(row.type())
              + "  "
              + renderUrl(row.url())
              + "  "
              + renderConfig(row.configJson())
              + "  "
              + render(row.description()));
    }
  }

  /** 列可空（外部写入），空值渲染成占位符而不是空串——空串会让列看起来错位。 */
  private static String render(String value) {
    return value == null || value.isBlank() ? ABSENT : value;
  }

  /** 见类 javadoc 的输出纪律：只有纯占位符可以原样打印。 */
  private static String renderUrl(String url) {
    if (url == null || url.isBlank()) {
      return ABSENT;
    }
    return PURE_PLACEHOLDER.matcher(url).matches() ? url : CONFIGURED;
  }

  /** 只列键名，值一律不打。 */
  private static String renderConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return ABSENT;
    }
    JsonNode node;
    try {
      node = JSON.readTree(configJson);
    } catch (JsonProcessingException e) {
      // 库里这一列是 TEXT 且由外部写入（读路径也只认「JSON 对象、值均为字符串」），解析不了只说明这行是脏的。
      // 打不开就打不开——不回显原值，也不让整张表因为一行坏数据打不出来。
      return UNPARSABLE;
    }
    if (node == null || !node.isObject()) {
      return UNPARSABLE;
    }
    List<String> keys = new ArrayList<>();
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      keys.add(names.next());
    }
    return keys.isEmpty() ? ABSENT : String.join(",", keys);
  }

  /**
   * 一行输出。
   *
   * @param name 渠道注册名
   * @param type 渠道类型
   * @param url 原始地址（含 token，渲染时按纪律替换）
   * @param configJson 原始配置 JSON（值含凭证，渲染时只取键名）
   * @param description 人类可读说明
   */
  private record Row(String name, String type, String url, String configJson, String description) {}
}
