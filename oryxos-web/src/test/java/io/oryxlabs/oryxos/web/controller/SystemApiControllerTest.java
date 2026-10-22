package io.oryxlabs.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.web.health.DatabaseHealthProbe;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 系统状态两端点的验收测试（contracts/rest-endpoints.md §4）。
 *
 * <p>守点：{@code status} <b>只由 db 决定</b>（FR-020：未就绪的 Provider MUST NOT 拉低整体状态）；{@code providers}
 * 的键集合恒等于 {@code SUPPORTED_PROVIDERS}，值只表达「配置就绪」、<b>不发真实网络探测</b>（裁决 ⑤）；{@code /info} 四个计数各取各的数据源。
 */
@WebMvcTest(controllers = SystemApiController.class)
class SystemApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DatabaseHealthProbe databaseHealthProbe;

  @MockitoBean private ProviderProperties providerProperties;

  @MockitoBean private ProfileRegistry profileRegistry;

  @MockitoBean private SessionService sessionService;

  @MockitoBean private ToolRegistry toolRegistry;

  @Test
  @DisplayName("db DOWN + provider 已配置 → 整体 DOWN（status 只由 db 决定）")
  void statusIsDownWhenDbIsDown() throws Exception {
    when(databaseHealthProbe.probe()).thenReturn(DatabaseHealthProbe.Level.DOWN);
    givenDeclaredProviders("deepseek");

    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("DOWN"))
        .andExpect(jsonPath("$.data.db").value("DOWN"))
        .andExpect(jsonPath("$.data.providers.deepseek").value("UP"));
  }

  @Test
  @DisplayName("db UP + 未声明 Provider → 整体仍 UP，该厂商为 DOWN（未就绪不拉低整体）")
  void providersDoNotLowerOverallStatus() throws Exception {
    when(databaseHealthProbe.probe()).thenReturn(DatabaseHealthProbe.Level.UP);
    givenDeclaredProviders();

    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("UP"))
        .andExpect(jsonPath("$.data.db").value("UP"))
        .andExpect(jsonPath("$.data.providers.deepseek").value("DOWN"));
  }

  @Test
  @DisplayName("/info：四个计数各取各的数据源，version 优先读构建注入的资源（不是只读 manifest）")
  void info_reportsCountsFromTheirSources() throws Exception {
    when(profileRegistry.all()).thenReturn(List.of(mock(Profile.class), mock(Profile.class)));
    when(sessionService.countActive()).thenReturn(7L);
    when(toolRegistry.registered()).thenReturn(toolEntries(15));

    // 测试资源 oryxos-version.properties 固定为 9.9.9-test：断言版本真的来自它。
    // 只写 isString() 的话，「打包后恒为 unknown」这个缺陷（vunknown）照样绿
    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("oryxos"))
        .andExpect(jsonPath("$.data.version").value("9.9.9-test"))
        .andExpect(jsonPath("$.data.javaVersion").isString())
        .andExpect(jsonPath("$.data.agents").value(2))
        .andExpect(jsonPath("$.data.activeSessions").value(7))
        .andExpect(jsonPath("$.data.registeredTools").value(15))
        .andExpect(jsonPath("$.data.uptimeSeconds").isNumber());
  }

  /** 只声明给定的厂商；凭证是「在场」的判据之一（未声明即未就绪）。 */
  private void givenDeclaredProviders(String... names) {
    List<ProviderProperties.ProviderEntry> entries = new ArrayList<>();
    for (String name : names) {
      entries.add(new ProviderProperties.ProviderEntry(name, "test-key"));
    }
    when(providerProperties.providers()).thenReturn(entries);
  }

  private static List<ToolRegistry.RegisteredTool> toolEntries(int count) {
    List<ToolRegistry.RegisteredTool> entries = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      entries.add(
          new ToolRegistry.RegisteredTool(
              mock(io.oryxlabs.oryxos.core.tool.OryxTool.class), "builtin"));
    }
    return entries;
  }
}
