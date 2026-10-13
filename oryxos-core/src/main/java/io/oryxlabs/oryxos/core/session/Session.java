package io.oryxlabs.oryxos.core.session;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 一次对话的上下文容器（data-model.md §1）。
 *
 * <p><b>本节交付的是内存态形态</b>。持久化形态（表 {@code sessions}、{@code SessionRepository}、 {@code SessionService}
 * 的实现）属第 18 节；本节只保证「经接缝被调用一次」。
 *
 * <p>四条不变量（contracts/session-model.md §3）：
 *
 * <ol>
 *   <li><b>只追加</b>：{@code messages} 不删除、不重排。截断发生在 {@code PromptBuilder} 组装 prompt 时，<b>不改动
 *       Session</b>——SC-004 要求事后能查到本次全部模型响应与工具结果。
 *   <li><b>文本与工具请求同属一条消息</b>：某一轮 assistant 消息里既有文本又有工具调用时，是一条消息，不拆两条。
 *   <li><b>{@code SYSTEM} 角色不写入</b>：system prompt 每轮现拼（FR-004），不属于会话历史。
 *   <li><b>非线程安全</b>：一次处理独占一个虚拟线程（章程原则 I 的同步阻塞模型），不加锁。
 * </ol>
 */
public final class Session {

  private final String id;
  private final String profileName;
  private final List<SessionMessage> messages = new ArrayList<>();
  private final Instant createdAt;
  private Instant lastActiveAt;

  private Session(String id, String profileName, Instant createdAt) {
    this.id = Objects.requireNonNull(id, "id");
    this.profileName = Objects.requireNonNull(profileName, "profileName");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    this.lastActiveAt = createdAt;
  }

  /**
   * 新建一个会话。
   *
   * @param id 会话标识。生成规则（{@code channel:user:profile} 三段联合）属第 18 节，本节由调用方传入
   * @param profileName 所属 Agent 名；{@code AgentService} 靠它从 {@code ProfileRegistry} 取
   *     Profile（FR-009）
   * @return 空的会话
   */
  public static Session create(String id, String profileName) {
    return new Session(id, profileName, Instant.now());
  }

  /**
   * @return 会话标识
   */
  public String id() {
    return id;
  }

  /**
   * @return 所属 Agent 名
   */
  public String profileName() {
    return profileName;
  }

  /**
   * @return 按序累积的会话历史，只读视图
   */
  public List<SessionMessage> messages() {
    return List.copyOf(messages);
  }

  /**
   * @return 创建时间
   */
  public Instant createdAt() {
    return createdAt;
  }

  /**
   * @return 最后一次追加消息的时间
   */
  public Instant lastActiveAt() {
    return lastActiveAt;
  }

  /**
   * 追加本次处理的用户输入（循环第一步）。
   *
   * @param text 用户输入
   * @return 刚追加的那条消息
   */
  public SessionMessage appendUserMessage(String text) {
    return append(new SessionMessage(SessionMessage.Role.USER, text, List.of(), null, null));
  }

  /**
   * 追加模型响应。文本与工具请求同属一条消息，不拆两条。
   *
   * @param text 模型文本；无文本时传空串
   * @param toolCalls 本轮请求的工具调用；无则传空表或 {@code null}
   * @return 刚追加的那条消息
   */
  public SessionMessage appendAssistantMessage(
      String text, List<AssistantMessage.ToolCall> toolCalls) {
    return append(new SessionMessage(SessionMessage.Role.ASSISTANT, text, toolCalls, null, null));
  }

  /**
   * 追加工具执行结果。
   *
   * <p>把 {@link ToolResult} 摊平成 {@code content}：成功取 {@code result.content()}，失败取 {@code
   * result.errorMessage()}。成败与否的权威记录是 {@code tool_invocations}，不是会话消息——这样模型能看见失败原因并换个办法
   * （FR-007），而审计口径不被消息形状绑架。
   *
   * @param toolCallId 对应的工具请求标识
   * @param result 工具执行结果
   * @return 刚追加的那条消息
   */
  public SessionMessage appendToolResult(String toolCallId, ToolResult result) {
    String text = result.success() ? result.content() : result.errorMessage();
    return append(new SessionMessage(SessionMessage.Role.TOOL, text, List.of(), toolCallId, null));
  }

  /** 追加收口：统一盖时间戳并刷新活跃时间，使「追加顺序即事实顺序」只由一处保证。 */
  private SessionMessage append(SessionMessage message) {
    SessionMessage stamped =
        new SessionMessage(
            message.role(),
            message.content(),
            message.toolCalls(),
            message.toolCallId(),
            Instant.now());
    messages.add(stamped);
    lastActiveAt = stamped.timestamp();
    return stamped;
  }
}
