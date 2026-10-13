package io.oryxlabs.oryxos.core.engine;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.context.ContextLoader;
import io.oryxlabs.oryxos.core.memory.MemorySource;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionMessage;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把「这一轮要发给模型的内容」按固定四部分顺序拼成 {@link Prompt}（FR-004）。
 *
 * <table>
 *   <caption>四部分的落点</caption>
 *   <tr><th>#</th><th>部分</th><th>落点</th><th>缺省行为</th></tr>
 *   <tr><td>①</td><td>system prompt = {@code identity.prompt} + {@link ContextLoader} + 末尾当前日期时间</td>
 *       <td>第 1 条 {@code SystemMessage}</td><td>{@code identity.prompt} 为空则不占位；日期时间行必在末尾</td></tr>
 *   <tr><td>②</td><td>长期记忆</td><td>第 2 条 {@code SystemMessage}</td>
 *       <td>为空则<b>整条不出现</b>，不塞占位文本</td></tr>
 *   <tr><td>③</td><td>会话历史（截断后）</td><td>其后的对话消息</td><td>会话为空时只剩本轮用户消息</td></tr>
 *   <tr><td>④</td><td>当前可用工具列表</td><td>{@code getOptions()} 的 {@code toolCallbacks}</td>
 *       <td>无可用工具则空列表</td></tr>
 * </table>
 *
 * <p><b>截断只切在用户消息边界上</b>（FR-005）：保留最后 N 个用户往返（N = {@code settings.maxHistoryTurns()}，默认 20），从<b>第 N
 * 条用户消息</b>起截到末尾；不足 N 轮不截断。「一轮」= 一条用户消息 + 它引发的全部 assistant / tool 消息。
 * 因此结果必然以用户消息开头（SC-005）、工具请求与结果必然同段（FR-005）。
 *
 * <p><b>截断不改动 {@code Session} 本身</b>——只影响本次发给模型的内容，历史照旧完整可审计（SC-004）。
 *
 * <p><b>工具说明只翻译不执行</b>：产物 {@code ToolCallback.call(String)} 抛 {@link
 * UnsupportedOperationException}， 工具调度完全归 {@link ToolExecutor}（章程原则 III，最易写错的一条）。
 */
public class PromptBuilder {

  private static final String DATE_TIME_PREFIX = "当前时间：";

  private final ContextLoader contextLoader;
  private final MemorySource memorySource;
  private final ToolCatalog toolCatalog;

  /**
   * <b>关于 {@code @SuppressFBWarnings}</b>：SpotBugs 对「方法较多、含循环」的类会放弃证明其不可变、回退判为「可变」，
   * 于是按依赖注入惯例持有协作对象被误报成 EI_EXPOSE_REP2。{@link ContextLoader} 每次调用都现读文件、不持跨调用缓存 （FR-009），唯一字段 {@code
   * oryxosDir} 是不可变 {@link java.nio.file.Path}，无共享可变状态可言——此处确为误报， 就地豁免并留下理由，比在全局 excludeFilterFile
   * 里开口子更可审计。
   *
   * @param contextLoader 读 Bootstrap / Agent 正文 / Skill 绑定的文件部分
   * @param memorySource 读跨会话的长期记忆
   * @param toolCatalog 列本次可用的工具子集
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "ContextLoader 是注入的无状态协作对象，不是本类对外暴露的内部状态，属 SpotBugs 误报")
  public PromptBuilder(
      ContextLoader contextLoader, MemorySource memorySource, ToolCatalog toolCatalog) {
    this.contextLoader = contextLoader;
    this.memorySource = memorySource;
    this.toolCatalog = toolCatalog;
  }

  /**
   * 组装本轮发给模型的内容。
   *
   * @param session 会话（只读，本方法不改动它）
   * @param profile 当前 Agent 的运行时配置
   * @return 四部分齐备的 prompt
   */
  public Prompt build(Session session, Profile profile) {
    List<Message> instructions = new ArrayList<>();
    instructions.add(new SystemMessage(systemPrompt(profile)));
    memorySource
        .longTermMemory(profile)
        .filter(memory -> !memory.isBlank())
        .ifPresent(memory -> instructions.add(new SystemMessage(memory)));
    instructions.addAll(historyMessages(session, profile));
    return new Prompt(instructions, options(profile));
  }

  /** ① system prompt：人格 + 文件部分 + 末尾日期时间。 */
  private String systemPrompt(Profile profile) {
    StringBuilder text = new StringBuilder();
    String persona = profile.identity().prompt();
    if (persona != null && !persona.isBlank()) {
      text.append(persona).append(System.lineSeparator());
    }
    String filePart = contextLoader.load(profile);
    if (!filePart.isEmpty()) {
      text.append(filePart);
      if (!filePart.endsWith(System.lineSeparator())) {
        text.append(System.lineSeparator());
      }
    }
    // 日期时间行必在末尾：模型据此判断「今天」是哪天，放在中间会被文件内容淹没
    text.append(DATE_TIME_PREFIX).append(LocalDateTime.now());
    return text.toString();
  }

  /** ④ 工具列表：走原生 Function Calling，因此挂在 options 上而不是写进文本。 */
  private ToolCallingChatOptions options(Profile profile) {
    DefaultToolCallingChatOptions.Builder options = DefaultToolCallingChatOptions.builder();
    options.toolCallbacks(toToolCallbacks(toolCatalog.availableFor(profile)));
    return options.build();
  }

  /** ③ 会话历史：先按用户消息边界截断，再逐条映射成 Spring AI 消息。 */
  private List<Message> historyMessages(Session session, Profile profile) {
    List<SessionMessage> history =
        truncate(session.messages(), profile.settings().maxHistoryTurns());
    Map<String, String> toolNames = toolNames(history);
    List<Message> messages = new ArrayList<>(history.size());
    for (SessionMessage message : history) {
      messages.add(toMessage(message, toolNames));
    }
    return messages;
  }

  /**
   * 保留最后 {@code maxTurns} 个用户往返。
   *
   * <p>切点只落在用户消息上，因此结果必然以用户消息开头——这是「工具请求与结果永远同段」的来源，也是 SC-005 的判定依据。
   */
  private static List<SessionMessage> truncate(List<SessionMessage> messages, int maxTurns) {
    List<Integer> userIndices = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      if (messages.get(i).role() == SessionMessage.Role.USER) {
        userIndices.add(i);
      }
    }
    if (userIndices.size() <= maxTurns) {
      return messages;
    }
    return List.copyOf(
        messages.subList(userIndices.get(userIndices.size() - maxTurns), messages.size()));
  }

  /**
   * 反查工具名：{@code SessionMessage} 的元素形状没有 {@code toolName} 字段，而 Spring AI 的 {@code ToolResponse}
   * 三元组必须带 {@code name}。
   *
   * <p>反查<b>必然命中</b>：截断只切在用户消息边界，assistant 工具请求与对应 tool 结果永远同段出现。
   */
  private static Map<String, String> toolNames(List<SessionMessage> history) {
    Map<String, String> names = new HashMap<>(Math.max(history.size(), 1));
    for (SessionMessage message : history) {
      for (AssistantMessage.ToolCall call : message.toolCalls()) {
        names.put(call.id(), call.name());
      }
    }
    return names;
  }

  /** ③ 的角色映射。用 if/else 链而非增强 switch：P3C/ASM 解析不了箭头形式的 switch（语法禁区）。 */
  private static Message toMessage(SessionMessage message, Map<String, String> toolNames) {
    String content = message.content() == null ? "" : message.content();
    if (message.role() == SessionMessage.Role.USER) {
      return new UserMessage(content);
    }
    if (message.role() == SessionMessage.Role.ASSISTANT) {
      return AssistantMessage.builder().content(content).toolCalls(message.toolCalls()).build();
    }
    if (message.role() == SessionMessage.Role.TOOL) {
      String name = toolNames.get(message.toolCallId());
      return ToolResponseMessage.builder()
          .responses(
              List.of(
                  new ToolResponseMessage.ToolResponse(
                      message.toolCallId(), name == null ? "" : name, content)))
          .build();
    }
    return new SystemMessage(content);
  }

  /** ④ 的翻译：只搬 name / description / inputSchema 三项，不搬运执行能力。 */
  private static List<ToolCallback> toToolCallbacks(List<OryxTool> tools) {
    List<ToolCallback> callbacks = new ArrayList<>(tools.size());
    for (OryxTool tool : tools) {
      callbacks.add(new SchemaOnlyToolCallback(tool));
    }
    return callbacks;
  }

  /**
   * 只携带 schema 的 {@link ToolCallback}：{@code call} 一律抛异常。
   *
   * <p>这样即使有人误把本产物接进框架的自动执行路径，也会立刻炸出来，而不是悄悄绕过 {@link ToolExecutor} 的统一审计。
   */
  private record SchemaOnlyToolCallback(OryxTool tool) implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return DefaultToolDefinition.builder()
          .name(tool.getName())
          .description(tool.getDescription())
          .inputSchema(tool.getInputSchema())
          .build();
    }

    @Override
    public String call(String toolInput) {
      throw new UnsupportedOperationException("工具说明只翻译不执行，执行权归 ToolExecutor: " + tool.getName());
    }
  }
}
