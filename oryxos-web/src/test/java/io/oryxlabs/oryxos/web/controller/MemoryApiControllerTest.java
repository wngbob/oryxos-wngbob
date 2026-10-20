package io.oryxlabs.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 长期记忆端点的验收测试（contracts/rest-endpoints.md §3.2）。
 *
 * <p>守点：形状是 {@code {core, archival, truncated}}；<b>不缓存</b>——两次调用之间记忆变了，第二次立刻看得见（FR-019）； 后端异常归
 * 500，<b>不伪装成空记忆</b>（把「读不到」渲染成「没有记忆」会让运维以为记忆丢了）。
 */
@WebMvcTest(controllers = MemoryApiController.class)
class MemoryApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private MemoryService memoryService;

  @Test
  @DisplayName("返回 {core, archival, truncated} 三分量")
  void returnsPartitionShape() throws Exception {
    when(memoryService.partition()).thenReturn(new MemoryPartition("核心内容", "归档内容", true));

    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.core").value("核心内容"))
        .andExpect(jsonPath("$.data.archival").value("归档内容"))
        .andExpect(jsonPath("$.data.truncated").value(true));
  }

  @Test
  @DisplayName("不缓存：两次调用之间记忆变了，第二次立刻看到新值（FR-019）")
  void doesNotCache() throws Exception {
    when(memoryService.partition())
        .thenReturn(new MemoryPartition("旧核心", "旧归档", false))
        .thenReturn(new MemoryPartition("新核心", "新归档", true));

    String first =
        mockMvc
            .perform(get("/api/v1/memory"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String second =
        mockMvc
            .perform(get("/api/v1/memory"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(first).contains("旧核心").doesNotContain("新核心");
    assertThat(second).contains("新核心").doesNotContain("旧核心");
  }

  @Test
  @DisplayName("后端异常 → 500，不伪装成空记忆，且响应体不泄漏异常描述")
  void backendFailure_returns500() throws Exception {
    when(memoryService.partition()).thenThrow(new IllegalStateException("MEMORY.md 读取失败"));

    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value(500))
        .andExpect(jsonPath("$.message").value("Internal server error"))
        .andExpect(content().string(not(containsString("MEMORY.md"))));
  }
}
