package io.oryxlabs.oryxos.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Agent 列表端点的验收测试（contracts/rest-endpoints.md §3.1）。
 *
 * <p>守点：<b>装载失败的 Agent 也在列表里</b>（{@code valid:false} + {@code error:<原因>}），不是静默消失——否则管理台上 「某个 Agent
 * 的 {@code AGENT.md} 写坏了」这件事根本看不见；列表按 {@code name} 升序，输出确定。
 */
@WebMvcTest(controllers = ProfileApiController.class)
class ProfileApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProfileLoader profileLoader;

  @Test
  @DisplayName("失败项与成功项同在一个列表：valid/error 如实反映，且按 name 升序")
  void list_includesFailedProfilesAndSortsByName() throws Exception {
    Profile zeta = profileOf("zeta");
    when(profileLoader.loadAllDetailed())
        .thenReturn(
            new ProfileLoader.LoadResult(
                List.of(zeta),
                List.of(new ProfileLoader.LoadResult.Failure("broken", "缺少 AGENT.md"))));

    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("broken"))
        .andExpect(jsonPath("$.data[0].valid").value(false))
        .andExpect(jsonPath("$.data[0].error").value("缺少 AGENT.md"))
        .andExpect(jsonPath("$.data[1].name").value("zeta"))
        .andExpect(jsonPath("$.data[1].valid").value(true))
        .andExpect(jsonPath("$.data[1].error").value(nullValue()));
  }

  @Test
  @DisplayName("成功项带 provider / tools / schedules，形状与契约逐字一致")
  void list_mapsProfileFields() throws Exception {
    when(profileLoader.loadAllDetailed())
        .thenReturn(new ProfileLoader.LoadResult(List.of(profileOf("daily-weather")), List.of()));

    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].description").value("每日天气播报"))
        .andExpect(jsonPath("$.data[0].provider.name").value("deepseek"))
        .andExpect(jsonPath("$.data[0].provider.model").value("deepseek-chat"))
        .andExpect(jsonPath("$.data[0].tools[0]").value("http_get"))
        .andExpect(jsonPath("$.data[0].schedules[0].cron").value("0 8 * * *"))
        .andExpect(jsonPath("$.data[0].schedules[0].zone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.data[0].schedules[0].message").value("播报今天天气"));
  }

  private static Profile profileOf(String name) {
    return new Profile(
        name,
        "每日天气播报",
        null,
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of("http_get"),
        null,
        null,
        List.of(new Profile.ScheduleSpec("0 8 * * *", "Asia/Shanghai", "播报今天天气")),
        null,
        null);
  }
}
