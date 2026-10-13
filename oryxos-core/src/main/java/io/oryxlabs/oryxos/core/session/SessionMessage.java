package io.oryxlabs.oryxos.core.session;

import java.time.Instant;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 会话历史的一条消息（data-model.md §2）。
 *
 * <p>字段口径逐条对齐 {@code specs/000-runtime-kernel/data-model.md} A1 的 {@code messages_json} 元素 {@code
 * {role, content, toolCalls?, toolCallId?, timestamp}}——第 18 节序列化落库时不需要改这个类型。
 *
 * <p><b>为什么 tool 消息不存工具名</b>：A1 的元素形状里没有该字段。重建 Spring AI 的 {@code ToolResponseMessage} 时，
 * 工具名由同一段历史里 assistant 消息的 {@code toolCalls} 反查得到（{@code PromptBuilder} 负责）。
 *
 * <p>构造由 {@link Session} 的三个追加操作统一收口，不另设工厂方法。
 *
 * @param role 角色，对应 A1 的 {@code role ∈ system/user/assistant/tool}
 * @param content 文本内容；空串表示无文本（assistant 可能只有工具请求；tool 承载结果或失败原因）
 * @param toolCalls 仅 {@code ASSISTANT} 非空：本轮模型请求的工具调用；元素是 Spring AI 的 record {@code (id, type,
 *     name, arguments)}
 * @param toolCallId 仅 {@code TOOL} 非空：对应哪一次工具请求
 * @param timestamp 追加时刻
 */
public record SessionMessage(
    Role role,
    String content,
    List<AssistantMessage.ToolCall> toolCalls,
    String toolCallId,
    Instant timestamp) {

  /**
   * 消息角色。
   *
   * <p>{@code SYSTEM} 保留但本节不产生：system prompt 每轮现拼（FR-004），不属于会话历史。枚举建全只是为了 A1 的序列化口径不用改。
   */
  public enum Role {
    /** 系统消息。本节不产生：system prompt 每轮现拼，不属于会话历史。 */
    SYSTEM,
    /** 用户输入。 */
    USER,
    /** 模型响应（可能同时带文本与工具请求）。 */
    ASSISTANT,
    /** 工具执行结果。 */
    TOOL
  }

  /** 归一化：{@code toolCalls} 为 {@code null} 时收敛为不可变空表，下游不必处处判空。 */
  public SessionMessage {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }
}
