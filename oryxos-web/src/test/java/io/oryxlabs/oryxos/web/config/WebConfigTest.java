package io.oryxlabs.oryxos.web.config;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.web.controller.ProfileApiController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 静态资源与跨域配置的判卷（contracts/admin-console.md §2、rest-endpoints.md §4）。
 *
 * <p><b>为什么用 {@code ProfileApiController} 当切片锚点</b>：本类要验的两件事都不属于任何控制器——SPA 回落挂在 {@code /admin/**}
 * 上、CORS 挂在 {@code /api/**} 上。切片必须至少放行一个真实控制器，才能让 {@code /api/**} 上有处理器可命中（预检请求走不到未映射的路径，未映射时
 * Spring 直接 404、不加 CORS 头）；{@code ProfileApiController} 是依赖最少的一个（一个 {@code ProfileLoader}）。
 *
 * <p><b>夹具为什么会赢</b>：{@code -Dfrontend.skip=true} 时主资源目录里的 {@code static/admin/} 是空的， 判「回落 vs
 * 404」必须有一份 {@code index.html} 在 classpath 上，故 T004/T040 各放了一份测试夹具。 两者同名时 surefire 的 classpath 顺序让
 * {@code target/test-classes} 先命中——若哪天顺序反了， {@link #adminRoot_servesSpaIndex()}
 * 会因为找不到夹具标记而变红，不会静默放过。
 */
@WebMvcTest(controllers = ProfileApiController.class)
class WebConfigTest {

  /** T004 夹具里的标记串，用来区分「回落到管理台入口」和「静态资源里真的有个同名文件」。 */
  private static final String FIXTURE_MARKER = "spa-fallback-fixture";

  /** T040 新增的资源夹具，只用来读缓存头。 */
  private static final String ASSET_MARKER = "probe-asset-fixture";

  /** 一年的秒数：`max-age=31536000` 是长缓存的判据，不是随手写的数字。 */
  private static final String ONE_YEAR_SECONDS = "31536000";

  private static final String ORIGIN = "http://console.example.com";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProfileLoader profileLoader;

  @Test
  void adminRoot_forwardsToSpaIndex() throws Exception {
    // 空资源路径在 ResourceHttpRequestHandler 里被直接判成 null（进不到解析链），
    // 故 /admin/ 走的是视图控制器转发；转发目标能否真拿到文件由下一条用例判
    mockMvc
        .perform(get("/admin/"))
        .andExpect(status().isOk())
        .andExpect(forwardedUrl("/admin/index.html"));
  }

  @Test
  void adminIndexHtml_servesSpaIndex() throws Exception {
    mockMvc
        .perform(get("/admin/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(FIXTURE_MARKER)));
  }

  @Test
  void adminSubRoute_fallsBackToIndexSoRefreshDoesNot404() throws Exception {
    // /admin/sessions 磁盘上不存在：浏览器刷新子路由打的正是这个路径（FR-016）
    mockMvc
        .perform(get("/admin/sessions"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(FIXTURE_MARKER)))
        .andExpect(header().string("Cache-Control", containsString("no-cache")));
  }

  @Test
  void adminWithoutTrailingSlash_redirectsToAdminRoot() throws Exception {
    // 少了尾部斜杠时相对路径的静态资源会解析到根上，故显式补一个重定向
    mockMvc.perform(get("/admin")).andExpect(status().is3xxRedirection());
  }

  @Test
  void adminAssets_servedWithImmutableLongCache() throws Exception {
    mockMvc
        .perform(get("/admin/assets/probe-asset.css"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(ASSET_MARKER)))
        .andExpect(
            header()
                .string(
                    "Cache-Control",
                    allOf(
                        containsString("max-age=" + ONE_YEAR_SECONDS),
                        containsString("immutable"))));
  }

  @Test
  void missingAdminAsset_isReal404NotFallback() throws Exception {
    // assets 下未命中就是真 404：回落成功会把「构建产物缺失」伪装成一个能打开的页面
    mockMvc
        .perform(get("/admin/assets/does-not-exist.js"))
        .andExpect(status().isNotFound())
        .andExpect(content().string(not(containsString(FIXTURE_MARKER))));
  }

  @Test
  void unknownApiPath_isNotFallbackToIndex() throws Exception {
    // FR-016：/api/v1/** 未命中保持 404，MUST NOT 被管理台回落吞掉
    mockMvc
        .perform(get("/api/v1/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(content().string(not(containsString(FIXTURE_MARKER))));
  }

  @Test
  void apiRequest_fromAnyOrigin_isAllowed() throws Exception {
    when(profileLoader.loadAllDetailed())
        .thenReturn(new ProfileLoader.LoadResult(List.of(), List.of()));
    mockMvc
        .perform(get("/api/v1/profiles").header("Origin", ORIGIN))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
  }

  @Test
  void apiPreflight_isAllowed() throws Exception {
    mockMvc
        .perform(
            options("/api/v1/profiles")
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
  }
}
