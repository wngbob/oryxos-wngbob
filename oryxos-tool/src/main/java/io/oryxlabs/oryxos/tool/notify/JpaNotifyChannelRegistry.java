package io.oryxlabs.oryxos.tool.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import io.oryxlabs.oryxos.storage.repository.NotifyChannelRepository;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link NotifyChannelRegistry} 的持久化实现：从 {@code notify_channels} 表的一行映射出一个通知目标。
 *
 * <p><b>本节只读</b>：构造只收一个 Repository，没有任何写入路径。
 *
 * <p><b>2026-10-15 修订（补强②）</b>：原文写「渠道的增删改归第 26 节（FR-014）」——那个挂账是错的，第 26 节的 10
 * 个端点里没有任何一个与渠道写入有关，而管理平台明确「这一版只读」。写入面已由 {@code oryxos notify-channel add|remove} 交付（三条**轻命令**，走原生
 * JDBC，不启 Spring，故不经过本类也不经过 Repository）。**本类仍然只读**——那条判断本身没变， 变的只是「写入面在哪」。
 *
 * <p><b>默认渠道的口径落在 SQL 层</b>：用 {@code findFirstByOrderByNameAsc()}（下推成 {@code ORDER BY name ASC
 * LIMIT 1}）， 不把整表读进内存再排序——SQLite 不保证无 {@code ORDER BY} 的行序，内存排序会把「哪一个是默认」变成依赖行序的偶然事实。
 *
 * <p><b>解析不到是错误，不是「没有」</b>：两种解析不到给两条<b>措辞不同</b>的报错——「一个都没配」与「你写错了名字」。 名字打错 MUST NOT
 * 静默退回默认渠道，否则消息会发到没人预期的群里（FR-011）。
 *
 * <p><b>不打日志、报错不带地址</b>：{@code url} 含 token。报错里只出现渠道名（注册名是人写的短标识，不是凭证）。
 *
 * <p><b>{@code ${ENV_VAR}} 在<b>读取时</b>解析，凭证不明文落库</b>（2026-10-15 补强②）：表里存的是字面量 {@code
 * ${FEISHU_HOOK_URL}}，解析渠道时才换成环境变量的值。原文写「那段口径随第 26 节的写入路径一并定，本节不假装实现了它」——
 * 写入面提前到本节交付后，这个「随写入路径一并定」的条件已经成立。读时解析而非写时解析的理由：db 文件被备份 / 误传时不泄凭证，轮换密钥只改环境变量、不必重新写一遍渠道。
 *
 * <p><b>变量缺失是错误，不是「用原样占位符发出去」</b>：与 {@code ProfileLoader.resolveEnv} 同口径——抛异常，消息里只出现
 * <b>变量名</b>、MUST NOT 出现变量值。这与同模块 {@code McpServerConfigLoader} 的「跳过整个 server + WARN」刻意不同： 那里一个
 * server 配错不该拖垮别的 server，而这里一个渠道配错<b>必须</b>让 {@code notify} 失败并落进审计表（FR-006 的同一精神）。
 *
 * <p><b>不抽公共的占位解析器</b>：{@code ProfileLoader} / {@code McpServerConfigLoader} / 本类各持一份正则——三处失败策略
 * 各不相同（抛 / 跳过 / 抛），共享解析循环反而要传入一个策略函数，比重复这段短逻辑更绕。三处语义、三份实现，重复是诚实的。
 */
public class JpaNotifyChannelRegistry implements NotifyChannelRegistry {

  /** {@link NotifyTarget#config()} 里承载地址的键。契约要求该键恒定存在（data-model.md §5 第 6 步）。 */
  private static final String URL_KEY = "url";

  /** 只用来读 {@code config_json}，不做序列化；{@code readTree} 是线程安全的。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 占位形如 {@code ${VAR_NAME}}；与 {@code ProfileLoader} / {@code McpServerConfigLoader} 逐字一致。 */
  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

  private final NotifyChannelRepository repository;
  private final UnaryOperator<String> envLookup;

  /**
   * 生产用法：环境变量占位从进程环境读取。
   *
   * @param repository {@code notify_channels} 表的存取出口
   */
  public JpaNotifyChannelRegistry(NotifyChannelRepository repository) {
    this(repository, System::getenv);
  }

  /**
   * @param repository {@code notify_channels} 表的存取出口
   * @param envLookup 环境变量查询函数；返回 {@code null} 表示未设置
   */
  JpaNotifyChannelRegistry(NotifyChannelRepository repository, UnaryOperator<String> envLookup) {
    this.repository = repository;
    this.envLookup = envLookup;
  }

  @Override
  public NotifyTarget resolve(String channelName) {
    // 空串 / 纯空白等同不传：调用方从模型那里拿到 "" 或 "   " 是常态，不该退化成「找名字叫空白的渠道」
    String name = channelName == null ? "" : channelName.trim();
    NotifyChannelEntity row = name.isEmpty() ? defaultRow() : rowByName(name);
    return toTarget(row);
  }

  private NotifyChannelEntity defaultRow() {
    return repository
        .findFirstByOrderByNameAsc()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "未配置任何通知渠道，无法推送——请先跑 `oryxos notify-channel add <name> --type <type>"
                        + " --url-env <ENV_VAR>` 添加一个渠道"));
  }

  private NotifyChannelEntity rowByName(String name) {
    return repository
        .findById(name)
        .orElseThrow(
            () -> new IllegalStateException("未找到名为 '" + name + "' 的通知渠道——请检查渠道名是否写错，或改用默认渠道"));
  }

  private NotifyTarget toTarget(NotifyChannelEntity row) {
    String type = row.getType();
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("通知渠道 '" + row.getName() + "' 的 type 为空，无法确定渠道类型");
    }
    Map<String, String> config = parseConfig(row);
    // url 键恒定放入（即使为空串）：让适配器的契约只剩「值是不是空白」一种形状要判
    config.put(
        URL_KEY, resolveEnv(row.getName(), URL_KEY, row.getUrl() == null ? "" : row.getUrl()));
    return new NotifyTarget(type, config);
  }

  /**
   * 把 {@code config_json} 解析成字符串键值对。
   *
   * <p><b>值非字符串即报错，不静默转字符串</b>：把 {@code 10} 悄悄变成 {@code "10"} 会把「配置写错了」伪装成「配置能用」，
   * 而错的那一行恰恰在真正推送时才炸。报错只提渠道名与出问题的键，不回显原始 JSON（里面可能有凭证）。
   */
  private Map<String, String> parseConfig(NotifyChannelEntity row) {
    Map<String, String> config = new LinkedHashMap<>();
    String raw = row.getConfigJson();
    if (raw == null || raw.isBlank()) {
      return config;
    }

    JsonNode node;
    try {
      node = JSON.readTree(raw);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("通知渠道 '" + row.getName() + "' 的 config_json 不是合法 JSON", e);
    }
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("通知渠道 '" + row.getName() + "' 的 config_json 必须是 JSON 对象");
    }

    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      if (!field.getValue().isTextual()) {
        throw new IllegalArgumentException(
            "通知渠道 '" + row.getName() + "' 的 config_json 里键 '" + field.getKey() + "' 的值不是字符串");
      }
      config.put(
          field.getKey(), resolveEnv(row.getName(), field.getKey(), field.getValue().asText()));
    }
    return config;
  }

  /**
   * 把 {@code ${VAR}} 换成环境变量的值。
   *
   * <p>无占位时原样返回（绝大多数渠道走这条路）。{@code ${A}-${B}} 这类混排逐个替换。
   *
   * <p><b>报错只带渠道名、键名与变量名</b>：三者都不是凭证；变量值 MUST NOT 出现（它才是密钥）。同 {@code ProfileLoader.resolveEnv} 的口径。
   */
  private String resolveEnv(String channelName, String key, String value) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(value);
    if (!matcher.find()) {
      return value;
    }
    StringBuilder resolved = new StringBuilder();
    do {
      String varName = matcher.group(1);
      String replacement = envLookup.apply(varName);
      if (replacement == null) {
        throw new IllegalStateException(
            "通知渠道 '"
                + channelName
                + "' 的 "
                + key
                + " 引用了未设置的环境变量 "
                + varName
                + "，无法解析占位 "
                + matcher.group()
                + "（报错不回显凭证值）");
      }
      matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
    } while (matcher.find());
    matcher.appendTail(resolved);
    return resolved.toString();
  }
}
