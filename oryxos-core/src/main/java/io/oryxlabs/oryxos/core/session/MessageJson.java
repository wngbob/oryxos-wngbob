package io.oryxlabs.oryxos.core.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * {@code messages_json} 落盘形状的唯一映射（contracts/session-persistence.md §3）。
 *
 * <p><b>为什么不让 {@link SessionMessage} 直接参与序列化</b>：给 {@code SessionMessage.Role} 加 Jackson 注解会改第 17
 * 节交付的公共类型，且把磁盘格式绑死在枚举命名上——改枚举名就悄悄改磁盘格式。落盘格式独立于内存类型， 是本类存在的全部理由。
 *
 * <p><b>形状</b>：数组，元素 {@code {role, content, toolCalls?, toolCallId?, timestamp}}——逐字对齐 {@code
 * specs/000-runtime-kernel/data-model.md} A1。
 *
 * <ul>
 *   <li>{@code role} 落<b>小写</b>（{@code system} / {@code user} / {@code assistant} / {@code tool}），
 *       枚举名是大写，故不能直接序列化。
 *   <li>{@code toolCalls} 每项只取 {@code {id, type, name, arguments}} 四个字段。
 *   <li>{@code timestamp} 用 {@code Instant.toString()}（ISO-8601 UTC，带 {@code Z}），往返无损。
 * </ul>
 *
 * <p><b>顺序即事实顺序</b>：数组顺序 = {@code Session.messages()} 顺序，读写都不得重排。
 *
 * <p><b>包级私有</b>：不是对外概念；磁盘格式的口径属于会话领域层，{@code oryxos-storage} 只把 {@code messages_json} 当一个不透明的
 * {@code String} 列。
 */
final class MessageJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 顶层是数组，元素是对象；元素内部结构由 {@link #fromElement} 逐个字段校验，故此处只取到 {@code Object}。 */
  private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};

  private static final String ROLE = "role";
  private static final String CONTENT = "content";
  private static final String TOOL_CALLS = "toolCalls";
  private static final String TOOL_CALL_ID = "toolCallId";
  private static final String TIMESTAMP = "timestamp";
  private static final String ID = "id";
  private static final String TYPE = "type";
  private static final String NAME = "name";
  private static final String ARGUMENTS = "arguments";

  /**
   * 落盘小写名 → 枚举。**在类初始化期一次建好**，读路径只做一次精确查表。
   *
   * <p>为什么不每次现算：现算意味着把<b>不可信入参</b>送进大小写映射（或拿常量映射结果去比它）， 而大小写映射随区域漂移（土耳其语的 i），SpotBugs 会按 {@code
   * IMPROPER_UNICODE} 拦下。把映射 收敛到常量一侧，读路径就是纯粹的字符串相等——既满足门禁，也少一次逐项循环。
   */
  private static final Map<String, SessionMessage.Role> ROLE_BY_JSON_NAME = roleIndex();

  private MessageJson() {}

  private static Map<String, SessionMessage.Role> roleIndex() {
    Map<String, SessionMessage.Role> index = new LinkedHashMap<>();
    for (SessionMessage.Role role : SessionMessage.Role.values()) {
      index.put(role.name().toLowerCase(Locale.ROOT), role);
    }
    return Map.copyOf(index);
  }

  /**
   * 序列化整段会话历史。
   *
   * @param messages 会话历史，顺序即事实顺序
   * @return JSON 数组文本
   * @throws IllegalStateException 序列化失败——按 A1 的校验规则，这是内部错误，MUST NOT 落半条数据
   */
  static String toJson(List<SessionMessage> messages) {
    List<Map<String, Object>> elements = new ArrayList<>(messages.size());
    for (SessionMessage message : messages) {
      elements.add(toElement(message));
    }
    try {
      return MAPPER.writeValueAsString(elements);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("会话历史序列化失败，本次不落库: " + e.getMessage(), e);
    }
  }

  /**
   * 反序列化整段会话历史。
   *
   * @param json JSON 数组文本；空串或 {@code null} 视为空历史
   * @return 会话历史，条数与顺序与写入前一致
   * @throws IllegalStateException 文本不是合法的落盘格式——库里存着读不回来的历史，比报错更糟
   */
  static List<SessionMessage> fromJson(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<Object> elements;
    try {
      elements = MAPPER.readValue(json, LIST_TYPE);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("会话历史反序列化失败: " + e.getMessage(), e);
    }
    List<SessionMessage> messages = new ArrayList<>(elements.size());
    for (Object element : elements) {
      messages.add(fromElement(element));
    }
    return List.copyOf(messages);
  }

  private static Map<String, Object> toElement(SessionMessage message) {
    Map<String, Object> element = new LinkedHashMap<>();
    element.put(ROLE, message.role().name().toLowerCase(Locale.ROOT));
    element.put(CONTENT, message.content());
    if (!message.toolCalls().isEmpty()) {
      List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
      for (AssistantMessage.ToolCall call : message.toolCalls()) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(ID, call.id());
        one.put(TYPE, call.type());
        one.put(NAME, call.name());
        one.put(ARGUMENTS, call.arguments());
        calls.add(one);
      }
      element.put(TOOL_CALLS, calls);
    }
    if (message.toolCallId() != null) {
      element.put(TOOL_CALL_ID, message.toolCallId());
    }
    element.put(TIMESTAMP, message.timestamp().toString());
    return element;
  }

  private static SessionMessage fromElement(Object raw) {
    if (!(raw instanceof Map<?, ?> element)) {
      throw new IllegalStateException("会话历史的元素必须是 JSON 对象，实际是 " + raw);
    }
    return new SessionMessage(
        roleOf(string(element, ROLE)),
        string(element, CONTENT),
        toolCalls(element.get(TOOL_CALLS)),
        string(element, TOOL_CALL_ID),
        Instant.parse(string(element, TIMESTAMP)));
  }

  /**
   * 把落盘的小写 role 还原成枚举。
   *
   * <p><b>只接受逐字的小写形式</b>（{@code system} / {@code user} / {@code assistant} / {@code tool}）， 与
   * {@link #toElement} 写出的形状严格互逆——读写对称，不做大小写不敏感匹配。
   */
  private static SessionMessage.Role roleOf(String role) {
    if (role == null) {
      throw new IllegalStateException("会话历史的元素缺少 role");
    }
    SessionMessage.Role resolved = ROLE_BY_JSON_NAME.get(role);
    if (resolved == null) {
      throw new IllegalStateException("会话历史的 role 取值非法: " + role);
    }
    return resolved;
  }

  private static List<AssistantMessage.ToolCall> toolCalls(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("会话历史的 toolCalls 必须是数组");
    }
    List<AssistantMessage.ToolCall> calls = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> one)) {
        throw new IllegalStateException("会话历史的 toolCalls 元素必须是 JSON 对象");
      }
      calls.add(
          new AssistantMessage.ToolCall(
              string(one, ID), string(one, TYPE), string(one, NAME), string(one, ARGUMENTS)));
    }
    return List.copyOf(calls);
  }

  /** 缺失字段返回 {@code null}，由各调用点决定是容忍（content / toolCallId）还是报错（role / timestamp）。 */
  private static String string(Map<?, ?> map, String key) {
    Object value = map.get(key);
    return value == null ? null : String.valueOf(value);
  }
}
