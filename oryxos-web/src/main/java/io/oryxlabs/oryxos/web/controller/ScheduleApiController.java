package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.schedule.ScheduledTaskStore;
import io.oryxlabs.oryxos.core.schedule.ScheduledTaskView;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.ScheduleExecutionView;
import io.oryxlabs.oryxos.web.controller.dto.ScheduleView;
import io.oryxlabs.oryxos.web.controller.dto.UpdateScheduleRequest;
import io.oryxlabs.oryxos.web.error.ScheduleNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定时任务管理端点（第 28 节）：列表 / 执行历史 / 立即执行 / 启用停用。
 *
 * <p><b>这是本仓第一个带写操作的 REST 面</b>（第 26 节的管理台只读）。写面刻意只有两个动作： <b>触发一次</b>与<b>改启用状态</b>——任务的 cron / 时区 /
 * 消息仍以 {@code AGENT.md} 为唯一真相源， REST 不做定义编辑，避免出现第二份定义。
 *
 * <p><b>本层只做三件事</b>：参数校验、响应包装、错误处理。真正的执行走 {@link AgentScheduler#runNow(String)}， 与到点触发汇入同一条 {@code
 * AgentService.process} 链路。
 *
 * <p><b>不缓存</b>：每次请求直接读 {@link ScheduledTaskStore}，管理台看到的就是库里的最新状态。
 */
@RestController
@RequestMapping("/api/v1/schedules")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 28 节交付的对外入口本身，不是缺陷")
public class ScheduleApiController {

  private final ScheduledTaskStore taskStore;
  private final AgentScheduler agentScheduler;

  /**
   * @param taskStore 任务登记与执行历史的读取面
   * @param agentScheduler 手动触发入口
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "taskStore / agentScheduler 是 Spring 注入的协作对象，构造器持有是依赖注入本身，"
              + "不构成对调用方的内部表示暴露（与 ProfileApiController 同款豁免）")
  public ScheduleApiController(ScheduledTaskStore taskStore, AgentScheduler agentScheduler) {
    this.taskStore = taskStore;
    this.agentScheduler = agentScheduler;
  }

  /**
   * 列出全部已登记任务及其运行状态。
   *
   * @return 按任务标识升序的任务视图
   */
  @GetMapping
  public ApiResponse<List<ScheduleView>> list() {
    return ApiResponse.ok(taskStore.list().stream().map(ScheduleView::from).toList());
  }

  /**
   * 某任务的执行历史，最近的在前。
   *
   * @param id 任务标识
   * @return 执行历史（可能为空——登记了但还没跑过）
   */
  @GetMapping("/{id}/executions")
  public ApiResponse<List<ScheduleExecutionView>> executions(@PathVariable String id) {
    requireTask(id);
    return ApiResponse.ok(
        taskStore.executions(id).stream().map(ScheduleExecutionView::from).toList());
  }

  /**
   * 立即执行一次（手动触发）：不等 cron、无视启用状态。
   *
   * <p>同步执行，返回体是<b>执行之后</b>的任务状态（{@code runCount} 已含本次），管理台点完按钮不必再查一次。
   *
   * @param id 任务标识
   * @return 执行后的任务视图
   */
  @PostMapping("/{id}/run")
  public ApiResponse<ScheduleView> run(@PathVariable String id) {
    requireTask(id);
    if (!agentScheduler.runNow(id)) {
      // 库里有行、调度器里没有：定义已从 AGENT.md 删除，仅剩历史行。本版没有 retire 机制，按不存在处理
      throw new ScheduleNotFoundException(id);
    }
    return ApiResponse.ok(ScheduleView.from(requireTask(id)));
  }

  /**
   * 启用 / 停用一条任务。
   *
   * @param id 任务标识
   * @param request 目标状态
   * @return 更新后的任务视图
   */
  @PutMapping("/{id}")
  public ApiResponse<ScheduleView> update(
      @PathVariable String id, @RequestBody UpdateScheduleRequest request) {
    requireTask(id);
    if (request == null || request.enabled() == null) {
      throw new IllegalArgumentException("enabled is required");
    }
    taskStore.setEnabled(id, request.enabled());
    return ApiResponse.ok(ScheduleView.from(requireTask(id)));
  }

  /**
   * 取任务视图，不存在即 404。
   *
   * @param id 任务标识
   * @return 任务视图
   */
  private ScheduledTaskView requireTask(String id) {
    return taskStore.find(id).orElseThrow(() -> new ScheduleNotFoundException(id));
  }
}
