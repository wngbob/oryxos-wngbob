package io.oryxlabs.oryxos.core.schedule;

import java.time.Instant;

/**
 * 定时任务的一次执行历史（第 28 节，表 {@code task_executions} 的读取形态）。
 *
 * <p><b>成功失败都记</b>：与 {@code llm_calls} / {@code tool_invocations} 同一条审计纪律——失败那次不写，运维就只剩
 * 「任务看起来没跑过」这一种解释，而真实原因可能是它每次都失败了。停用期间不执行、也不记历史。
 *
 * @param id 历史行主键（自增）；新建尚未落库时为 {@code null}
 * @param taskId 所属任务（与 {@link ScheduledTaskView#taskId()} 同值）
 * @param sessionId 本次触发所用的钟推会话；取会话这一步就失败时为 {@code null}
 * @param startedAt 执行开始时刻
 * @param success 是否成功（异常 / Provider 故障 / 工具失败导致的不正常返回都算失败）
 * @param errorMessage 失败原因；成功时为 {@code null}
 * @param durationMs 执行耗时（毫秒）
 */
public record TaskExecutionView(
    Long id,
    String taskId,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {}
