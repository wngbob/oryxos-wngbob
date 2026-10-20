package io.oryxlabs.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 工具列表端点的验收测试（contracts/rest-endpoints.md §3.3）。
 *
 * <p>守点：{@code source} 如实反映注册来源（{@code builtin} / {@code annotation} / {@code mcp:<server>}）；
 * {@code origin} 对非 MCP 工具与 {@code source} 相同，对 MCP 工具是去掉前缀的 server 名——管理台据此一眼看出工具从哪来。
 */
@WebMvcTest(controllers = ToolApiController.class)
class ToolApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ToolRegistry toolRegistry;

  @Test
  @DisplayName("三种来源：source 原样、origin 对 MCP 去前缀，其余与 source 相同")
  void mapsSourceAndOriginForEveryKind() throws Exception {
    // 先把三个条目造好再 stub：在 when(...) 的实参里造 mock 并给它打桩，会被 Mockito 判成 "Unfinished stubbing"
    List<ToolRegistry.RegisteredTool> entries =
        List.of(
            entry("read_file", "读取文本文件内容", "builtin"),
            entry("echo", "回显", "annotation"),
            entry("mcp_echo", "MCP 回显", "mcp:demo"));
    when(toolRegistry.registered()).thenReturn(entries);

    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.length()").value(3))
        .andExpect(jsonPath("$.data[0].name").value("read_file"))
        .andExpect(jsonPath("$.data[0].description").value("读取文本文件内容"))
        .andExpect(jsonPath("$.data[0].inputSchema").value("{\"type\":\"object\"}"))
        .andExpect(jsonPath("$.data[0].source").value("builtin"))
        .andExpect(jsonPath("$.data[0].origin").value("builtin"))
        .andExpect(jsonPath("$.data[1].source").value("annotation"))
        .andExpect(jsonPath("$.data[1].origin").value("annotation"))
        .andExpect(jsonPath("$.data[2].source").value("mcp:demo"))
        .andExpect(jsonPath("$.data[2].origin").value("demo"));
  }

  private static ToolRegistry.RegisteredTool entry(String name, String description, String source) {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn(name);
    when(tool.getDescription()).thenReturn(description);
    when(tool.getInputSchema()).thenReturn("{\"type\":\"object\"}");
    return new ToolRegistry.RegisteredTool(tool, source);
  }
}
