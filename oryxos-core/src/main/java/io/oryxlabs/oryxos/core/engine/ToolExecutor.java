package io.oryxlabs.oryxos.core.engine;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.storage.entity.ToolInvocation;
import io.oryxlabs.oryxos.storage.repository.ToolInvocationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * 工具执行的<b>唯一落点</b>：找到工具、执行、落审计、把结果包好返回给循环（FR-006 / FR-007）。
 *
 * <p>三种情形走同一条路径，三种情形都落 {@code tool_invocations}：
 *
 * <table>
 *   <caption>三种情形的处理</caption>
 *   <tr><th>情形</th><th>落库</th><th>返回</th></tr>
 *   <tr><td>{@code find} 查不到</td><td>{@code success=false}、{@code error_message="工具不存在: &lt;name&gt;"}</td>
 *       <td>{@link ToolResult#failure(String)}</td></tr>
 *   <tr><td>工具正常返回</td><td>{@code success = result.success()}</td><td>原样返回 {@code result}</td></tr>
 *   <tr><td>工具抛 {@code RuntimeException}</td><td>{@code success=false}、{@code error_message = e.getMessage()}</td>
 *       <td>{@link ToolResult#failure(String)}</td></tr>
 * </table>
 *
 * <p><b>三条不变量</b>：
 *
 * <ol>
 *   <li>每一次模型请求的工具调用都<b>恰好产生一行审计</b>（成功 / 失败 / 查不到工具都算），SC-006 覆盖率 100%；
 *   <li>每一次工具调用都<b>恰好输出一条结构化日志</b>（成功 INFO、失败 WARN，与第 16 节 {@code ProviderService} 同口径）。 失败 MUST
 *       NOT 被静默吞掉：原因进审计、进日志、也回灌给模型（下一轮的 tool 消息）；
 *   <li>工具异常 MUST NOT 打断循环：一律转成失败结果返回，不上抛。捕获类型是 {@code RuntimeException}——第 24 节的 {@code
 *       SandboxViolationException} 继承它，core 因此不需要 import 任何能力模块的异常类。
 * </ol>
 *
 * <p><b>本节只写不建</b>：不改 {@code tool_invocations} 表结构、不新建表、不新增 Repository 查询方法。
 *
 * <p><b>本节不做</b>：失败重试（{@code ToolResult.retryable} 原样透传、不消费）、入参 / 结果脱敏。
 */
public class ToolExecutor {

  private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

  private final ToolCatalog toolCatalog;
  private final ToolInvocationRepository repository;

  /**
   * @param toolCatalog 按名查工具的接缝（第 20 节由 {@code ToolRegistry} 实现）
   * @param repository 审计落库出口
   */
  public ToolExecutor(ToolCatalog toolCatalog, ToolInvocationRepository repository) {
    this.toolCatalog = toolCatalog;
    this.repository = repository;
  }

  /**
   * 执行一次工具调用，并落一条审计。
   *
   * @param sessionId 会话标识，写进 {@code tool_invocations.session_id}（FR-014）
   * @param call 模型请求的工具调用
   * @return 执行结果；工具不存在或抛异常时是携带原因的失败结果，<b>不上抛</b>
   */
  public ToolResult execute(String sessionId, AssistantMessage.ToolCall call) {
    long startedAt = System.currentTimeMillis();
    Optional<OryxTool> tool = toolCatalog.find(call.name());
    if (tool.isEmpty()) {
      String reason = "工具不存在: " + call.name();
      record(sessionId, call, false, null, reason, startedAt);
      return ToolResult.failure(reason);
    }
    try {
      ToolResult result = tool.get().execute(call.arguments());
      record(
          sessionId,
          call,
          result.success(),
          result.success() ? result.content() : null,
          result.success() ? null : result.errorMessage(),
          startedAt);
      return result;
    } catch (RuntimeException e) {
      // 不打断循环：转成失败结果返回，原因进审计、进日志、也回灌给模型（FR-007）
      String reason = messageOf(e);
      record(sessionId, call, false, null, reason, startedAt);
      return ToolResult.failure(reason);
    }
  }

  /** 落一条审计 + 一条结构化日志。成败两条路径都走这里，保证覆盖率恒为 100%。 */
  private void record(
      String sessionId,
      AssistantMessage.ToolCall call,
      boolean success,
      String resultJson,
      String errorMessage,
      long startedAt) {
    long durationMs = System.currentTimeMillis() - startedAt;
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId(sessionId);
    invocation.setToolName(call.name());
    invocation.setInputJson(call.arguments());
    invocation.setResultJson(resultJson);
    invocation.setSuccess(success);
    invocation.setErrorMessage(errorMessage);
    invocation.setDurationMs(durationMs);
    invocation.setCreatedAt(LocalDateTime.now());
    repository.save(invocation);

    if (success) {
      log.atInfo()
          .addKeyValue("event", "tool_invocation")
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("toolName", call.name())
          .addKeyValue("success", true)
          .addKeyValue("durationMs", durationMs)
          .log("工具调用完成");
    } else {
      log.atWarn()
          .addKeyValue("event", "tool_invocation")
          .addKeyValue("sessionId", sessionId)
          .addKeyValue("toolName", call.name())
          .addKeyValue("success", false)
          .addKeyValue("durationMs", durationMs)
          .addKeyValue("errorMessage", errorMessage)
          .log("工具调用失败");
    }
  }

  /** 异常消息可空（如 NPE），失败原因不能因此变成空——那等于把失败静默吞掉。 */
  private static String messageOf(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getName() : message;
  }
}
