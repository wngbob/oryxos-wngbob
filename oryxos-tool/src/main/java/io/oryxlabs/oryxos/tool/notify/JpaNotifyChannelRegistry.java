package io.oryxlabs.oryxos.tool.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import io.oryxlabs.oryxos.storage.repository.NotifyChannelRepository;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link NotifyChannelRegistry} 的持久化实现：从 {@code notify_channels} 表的一行映射出一个通知目标。
 *
 * <p><b>本节只读</b>：构造只收一个 Repository，没有任何写入路径——渠道的增删改归第 26 节（FR-014）。
 *
 * <p><b>默认渠道的口径落在 SQL 层</b>：用 {@code findFirstByOrderByNameAsc()}（下推成 {@code ORDER BY name ASC
 * LIMIT 1}）， 不把整表读进内存再排序——SQLite 不保证无 {@code ORDER BY} 的行序，内存排序会把「哪一个是默认」变成依赖行序的偶然事实。
 *
 * <p><b>解析不到是错误，不是「没有」</b>：两种解析不到给两条<b>措辞不同</b>的报错——「一个都没配」与「你写错了名字」。 名字打错 MUST NOT
 * 静默退回默认渠道，否则消息会发到没人预期的群里（FR-011）。
 *
 * <p><b>不打日志、报错不带地址</b>：{@code url} 含 token。报错里只出现渠道名（注册名是人写的短标识，不是凭证）。
 *
 * <p><b>不做 {@code ${ENV_VAR}} 占位解析</b>：那段口径随第 26 节的写入路径一并定，本节不假装实现了它。
 */
public class JpaNotifyChannelRegistry implements NotifyChannelRegistry {

  /** {@link NotifyTarget#config()} 里承载地址的键。契约要求该键恒定存在（data-model.md §5 第 6 步）。 */
  private static final String URL_KEY = "url";

  /** 只用来读 {@code config_json}，不做序列化；{@code readTree} 是线程安全的。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final NotifyChannelRepository repository;

  public JpaNotifyChannelRegistry(NotifyChannelRepository repository) {
    this.repository = repository;
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
        .orElseThrow(() -> new IllegalStateException("未配置任何通知渠道，无法推送——请先在通知渠道注册表里添加一个渠道"));
  }

  private NotifyChannelEntity rowByName(String name) {
    return repository
        .findById(name)
        .orElseThrow(
            () -> new IllegalStateException("未找到名为 '" + name + "' 的通知渠道——请检查渠道名是否写错，或改用默认渠道"));
  }

  private static NotifyTarget toTarget(NotifyChannelEntity row) {
    String type = row.getType();
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("通知渠道 '" + row.getName() + "' 的 type 为空，无法确定渠道类型");
    }
    Map<String, String> config = parseConfig(row);
    // url 键恒定放入（即使为空串）：让适配器的契约只剩「值是不是空白」一种形状要判
    config.put(URL_KEY, row.getUrl() == null ? "" : row.getUrl());
    return new NotifyTarget(type, config);
  }

  /**
   * 把 {@code config_json} 解析成字符串键值对。
   *
   * <p><b>值非字符串即报错，不静默转字符串</b>：把 {@code 10} 悄悄变成 {@code "10"} 会把「配置写错了」伪装成「配置能用」，
   * 而错的那一行恰恰在真正推送时才炸。报错只提渠道名与出问题的键，不回显原始 JSON（里面可能有凭证）。
   */
  private static Map<String, String> parseConfig(NotifyChannelEntity row) {
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
      config.put(field.getKey(), field.getValue().asText());
    }
    return config;
  }
}
