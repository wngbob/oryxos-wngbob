package io.oryxlabs.oryxos.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.schedule.ScheduledTaskStore;
import io.oryxlabs.oryxos.core.schedule.ScheduledTaskView;
import io.oryxlabs.oryxos.core.schedule.TaskExecutionView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 定时任务管理端点的验收测试（第 28 节「④ 管理：四个端点」）。
 *
 * <p>守点：列表带全部运行态字段；执行历史按存储层给出的顺序透出；「立即执行」恰调一次 {@code runNow} 且返回执行后的状态；任务不存在（含「库里有行、调度器里没有」）一律 404；
 * PUT 缺 {@code enabled} → 400；启用停用只改一个布尔值。
 *
 * <p>全程 {@code @WebMvcTest} 切片 + mock，不碰数据库、不碰调度线程。
 */
@WebMvcTest(controllers = ScheduleApiController.class)
class ScheduleApiControllerTest {

  private static final String TASK_ID = "daily-weather#1";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ScheduledTaskStore taskStore;

  @MockitoBean private AgentScheduler agentScheduler;

  @Test
  void list_returnsAllTasksWithRuntimeState() throws Exception {
    when(taskStore.list())
        .thenReturn(List.of(view(true, 2, ScheduledTaskView.STATUS_SUCCESS), view(false, 0, null)));

    mockMvc
        .perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].taskId").value(TASK_ID))
        .andExpect(jsonPath("$.data[0].profileName").value("daily-weather"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(jsonPath("$.data[0].runCount").value(2))
        .andExpect(jsonPath("$.data[0].lastStatus").value("success"))
        .andExpect(jsonPath("$.data[0].nextRunAt").exists())
        .andExpect(jsonPath("$.data[1].enabled").value(false))
        .andExpect(jsonPath("$.data[1].lastStatus").value(nullValue()));
  }

  @Test
  void executions_unknownTask_returns404() throws Exception {
    when(taskStore.find(TASK_ID)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/schedules/{id}/executions", TASK_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("schedule not found: " + TASK_ID));

    verify(taskStore, never()).executions(any());
  }

  @Test
  void executions_returnsHistoryInStoreOrder() throws Exception {
    when(taskStore.find(TASK_ID)).thenReturn(Optional.of(view(true, 2, "failed")));
    when(taskStore.executions(TASK_ID))
        .thenReturn(
            List.of(
                new TaskExecutionView(
                    2L,
                    TASK_ID,
                    "scheduler:scheduler:daily-weather",
                    Instant.parse("2026-10-20T01:00:00Z"),
                    false,
                    "boom",
                    12L),
                new TaskExecutionView(
                    1L,
                    TASK_ID,
                    "scheduler:scheduler:daily-weather",
                    Instant.parse("2026-10-19T01:00:00Z"),
                    true,
                    null,
                    10L)));

    mockMvc
        .perform(get("/api/v1/schedules/{id}/executions", TASK_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].success").value(false))
        .andExpect(jsonPath("$.data[0].errorMessage").value("boom"))
        .andExpect(jsonPath("$.data[1].success").value(true));
  }

  @Test
  void run_triggersOnceAndReturnsPostRunState() throws Exception {
    ScheduledTaskView before = view(true, 0, null);
    ScheduledTaskView after = view(true, 1, ScheduledTaskView.STATUS_SUCCESS);
    when(taskStore.find(TASK_ID)).thenReturn(Optional.of(before), Optional.of(after));
    when(agentScheduler.runNow(TASK_ID)).thenReturn(true);

    mockMvc
        .perform(post("/api/v1/schedules/{id}/run", TASK_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
        .andExpect(jsonPath("$.data.runCount").value(1))
        .andExpect(jsonPath("$.data.lastStatus").value("success"));

    verify(agentScheduler).runNow(TASK_ID);
  }

  @Test
  void run_unknownTask_returns404WithoutTriggering() throws Exception {
    when(taskStore.find(TASK_ID)).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/v1/schedules/{id}/run", TASK_ID)).andExpect(status().isNotFound());

    verify(agentScheduler, never()).runNow(any());
  }

  @Test
  @DisplayName("库里有行、调度器里没有（定义已从 AGENT.md 删除）→ 404")
  void run_taskNotRegistered_returns404() throws Exception {
    when(taskStore.find(TASK_ID)).thenReturn(Optional.of(view(true, 0, null)));
    when(agentScheduler.runNow(TASK_ID)).thenReturn(false);

    mockMvc
        .perform(post("/api/v1/schedules/{id}/run", TASK_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("schedule not found: " + TASK_ID));
  }

  @Test
  void update_togglesEnabledAndReturnsUpdatedState() throws Exception {
    when(taskStore.find(TASK_ID))
        .thenReturn(Optional.of(view(true, 0, null)), Optional.of(view(false, 0, null)));
    when(taskStore.setEnabled(TASK_ID, false)).thenReturn(true);

    mockMvc
        .perform(
            put("/api/v1/schedules/{id}", TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enabled", false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.runCount").value(0));

    verify(taskStore).setEnabled(TASK_ID, false);
  }

  @Test
  void update_missingEnabled_returns400() throws Exception {
    when(taskStore.find(TASK_ID)).thenReturn(Optional.of(view(true, 0, null)));

    mockMvc
        .perform(
            put("/api/v1/schedules/{id}", TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("enabled is required"));

    verify(taskStore, never()).setEnabled(any(), anyBoolean());
  }

  @Test
  void update_unknownTask_returns404() throws Exception {
    when(taskStore.find(TASK_ID)).thenReturn(Optional.empty());

    mockMvc
        .perform(
            put("/api/v1/schedules/{id}", TASK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("enabled", true))))
        .andExpect(status().isNotFound());
  }

  private static ScheduledTaskView view(boolean enabled, int runCount, String lastStatus) {
    return new ScheduledTaskView(
        TASK_ID,
        "daily-weather",
        "0 9 * * *",
        "Asia/Shanghai",
        "播报今天的天气",
        enabled,
        Instant.parse("2026-10-21T01:00:00Z"),
        runCount == 0 ? null : Instant.parse("2026-10-20T01:00:00Z"),
        lastStatus,
        runCount);
  }

  private String json(Object value) throws Exception {
    return objectMapper.writeValueAsString(value);
  }
}
