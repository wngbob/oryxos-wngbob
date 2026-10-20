package io.oryxlabs.oryxos.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 管理台读写边界的机器判卷（contracts/admin-console.md §4）。
 *
 * <p><b>修订注（2026-10-20，第 28 节）：</b>本类原判「整站只读」。第 28 节按课件交付管理台第一页 带写操作的「定时任务」页（立即执行 /
 * 启用停用），经用户裁决<b>精确开写口</b>：写方法只允许出现在 {@code SchedulesView.vue} 与唯一数据通道 {@code api.js}，且页面只能经 {@code
 * api.js} 的 {@code post} / {@code put} 打 {@code /api/v1/schedules/**}；其余六页仍逐文件判只读。
 * <b>断言只收紧不改松</b>：没有删任何一条，新增了「其它页面 MUST NOT 出现 post(/put(」与 「定时任务页 MUST NOT 直接 fetch」两条。
 *
 * <p><b>为什么读源码而不是跑浏览器</b>：本节前端是纯展示台，没有前端测试基建（research D3 已决定不挂 {@code npm test}）。
 * 源码断言零额外依赖、零构建时长，却能抓住「有人加了个删除按钮」「有人把路径写成完整 URL 绕过同源」这类回归。
 *
 * <p><b>判的是源码不是产物</b>：{@code src/main/frontend/} 随仓库入库、{@code -Dfrontend.skip=true} 时也在，
 * 故本类不受前端构建是否执行影响。
 */
class AdminConsoleSourceTest {

  private static final Path FRONTEND_ROOT = Paths.get("src", "main", "frontend");
  private static final Path SRC_ROOT = FRONTEND_ROOT.resolve("src");
  private static final Path VIEWS_ROOT = SRC_ROOT.resolve("views");
  private static final Path API_MODULE = SRC_ROOT.resolve("api.js");
  private static final Path SCHEDULES_VIEW = VIEWS_ROOT.resolve("SchedulesView.vue");
  private static final Path INDEX_HTML = FRONTEND_ROOT.resolve("index.html");

  /** 写操作动词：除定时任务页与 api.js 外出现任意一个就说明越界了。 */
  private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

  /** 页面里直接调写助手的形状（{@code input(} 之类的子串不算，故用词边界）。 */
  private static final Pattern WRITE_CALL = Pattern.compile("\\b(post|put)\\(");

  /** 写操作按钮文案（契约 §4 第 5 条给出的五个词）。 */
  private static final List<String> WRITE_BUTTON_TEXTS = List.of("新建", "删除", "编辑", "保存", "提交");

  /** 字符串字面量里的请求路径：只认以 `/` 开头的，用来区分「同源相对路径」与「硬编码主机名」。 */
  private static final Pattern API_PATH_LITERAL = Pattern.compile("[\"'`](/api[^\"'`]*)");

  /**
   * 七个只读页 ↔ 各自必须引用的端点（契约 §3 的映射表）。
   *
   * <p>概览页读的是运行时状态（Agent 数 / 活跃会话 / 工具数 / 运行时长 / 健康），端点全是既有那四个； 2026-10-20
   * 用户澄清后它不再是静态快照，故与其余各页一样受「页面不是空壳」这条判卷。
   *
   * <p>定时任务页在 2026-10-20（第 28 节）改为读 `GET /api/v1/schedules`：定义与运行状态（下次触发 / 上次结果 / 次数 /
   * 启用与否）一起由服务端给出，`AGENT.md` 仍是定义真相源。渠道页本轮仍不加， 渠道管理仍挂扩展阶段——`notify_channels` 至今没有查询端点。
   */
  private static final Map<String, List<String>> VIEW_ENDPOINTS = viewEndpoints();

  @Test
  void frontendSourcesExist() {
    assertThat(SRC_ROOT).as("前端源码目录").isDirectory();
    assertThat(INDEX_HTML).as("前端入口").isRegularFile();
    assertThat(API_MODULE).as("唯一数据通道").isRegularFile();
  }

  @Test
  void writeMethodsAreConfinedToSchedulesPage() throws IOException {
    for (Path file : sourceFiles()) {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      if (file.equals(API_MODULE)) {
        // 唯一数据通道：GET 显式声明；只开 POST / PUT 两个写助手，PATCH / DELETE 仍不许出现
        assertThat(source)
            .as("api.js 必须显式用 GET 发请求，并按第 28 节只导出 POST / PUT 两个写助手")
            .contains("fetch(")
            .contains("method: 'GET'")
            .contains("'POST'")
            .contains("'PUT'");
        for (String method : List.of("PATCH", "DELETE")) {
          assertThat(Pattern.compile("\\b" + method + "\\b").matcher(source).find())
              .as("api.js MUST NOT 出现 %s", method)
              .isFalse();
        }
        continue;
      }
      if (file.equals(SCHEDULES_VIEW)) {
        // 写口只打定时任务路径，且必须经 api.js——页面里直接 fetch 等于绕过唯一通道
        assertThat(source)
            .as("定时任务页的写操作只能打 /api/v1/schedules/**，且经 api.js")
            .contains("post('/api/v1/schedules/")
            .contains("put('/api/v1/schedules/")
            .doesNotContain("fetch(");
        for (String method : List.of("PATCH", "DELETE")) {
          assertThat(Pattern.compile("\\b" + method + "\\b").matcher(source).find())
              .as("SchedulesView.vue MUST NOT 出现 %s", method)
              .isFalse();
        }
        continue;
      }
      for (String method : WRITE_METHODS) {
        assertThat(Pattern.compile("\\b" + method + "\\b").matcher(source).find())
            .as("%s MUST NOT 出现 %s；除定时任务页外管理台仍是只读的", relative(file), method)
            .isFalse();
      }
      assertThat(WRITE_CALL.matcher(source).find())
          .as("%s MUST NOT 调用写助手 post/put；第 28 节的写口只开给定时任务页", relative(file))
          .isFalse();
    }
  }

  @Test
  void everyRequestPathIsUnderApiV1() throws IOException {
    List<String> paths = new ArrayList<>();
    for (Path file : sourceFiles()) {
      Matcher matcher = API_PATH_LITERAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
      while (matcher.find()) {
        paths.add(matcher.group(1));
      }
    }
    // 一条路径都没扫到，说明正则与源码形状脱节了——那比「有路径不合格」更该报警
    assertThat(paths).as("扫到的请求路径").isNotEmpty();
    assertThat(paths)
        .as("所有请求路径 MUST 以 /api/v1 开头：硬编码主机名或契约外路径都会破坏同源与契约")
        .allMatch(path -> path.startsWith("/api/v1"));
  }

  @Test
  void contractViewFilesExist() {
    assertThat(VIEWS_ROOT).isDirectory();
    assertThat(VIEW_ENDPOINTS.keySet())
        .allSatisfy(name -> assertThat(VIEWS_ROOT.resolve(name)).as(name).isRegularFile());
  }

  /**
   * 概览页（2026-10-20 用户裁决：导航第一项；同日澄清为**运行时状态总览**）。
   *
   * <p>断言两件事：① 它排在其余六页之前、`App.vue` 已注册（它是落地页）；② 它确实读取运行时状态， 而不是退化成产品介绍页——「OryxOS
   * 是什么」在官网上讲，不在这里重复（用户原话）。 端点引用由 {@link #eachViewReferencesItsEndpoint()} 按 §3 映射表判。
   */
  @Test
  void overviewPageIsLandingPage() throws IOException {
    Path overview = VIEWS_ROOT.resolve("OverviewView.vue");
    assertThat(overview).as("概览页视图").isRegularFile();

    String router = Files.readString(SRC_ROOT.resolve("router.js"), StandardCharsets.UTF_8);
    int overviewPosition = router.indexOf("'/overview'");
    assertThat(overviewPosition).as("router.js 应声明 /overview 路由").isGreaterThan(-1);
    for (String other :
        List.of(
            "'/sessions'", "'/profiles'", "'/tools'", "'/schedules'", "'/memory'", "'/status'")) {
      assertThat(router.indexOf(other))
          .as("概览应排在 %s 之前（它是落地页）", other)
          .isGreaterThan(overviewPosition);
    }

    String app = Files.readString(SRC_ROOT.resolve("App.vue"), StandardCharsets.UTF_8);
    assertThat(app)
        .as("App.vue 应导入并注册概览页")
        .contains("import OverviewView from './views/OverviewView.vue'")
        .contains("'/overview': OverviewView");

    String source = Files.readString(overview, StandardCharsets.UTF_8);
    assertThat(source).as("概览页必须读取运行时状态数据（四个既有端点），不是静态介绍页").contains("from '../api.js'");
  }

  /**
   * 会话页在 2026-10-20（第 27 节）从「粘贴会话 ID 查询」改成「先读列表、点一行看详情」。
   *
   * <p><b>为什么单独一条而不是只加进 {@link #VIEW_ENDPOINTS}</b>：映射表用的是 {@code contains}，而详情路径 {@code
   * /api/v1/sessions/{id}} 本身就包含列表路径这个前缀——只把它加进表里，判据会被详情调用「顺带满足」， 等于没判。这条断言卡住调用的字面形状：列表调用不带
   * id，详情调用带 id。
   */
  @Test
  void sessionsViewReadsTheListEndpoint() throws IOException {
    String source =
        Files.readString(VIEWS_ROOT.resolve("SessionsView.vue"), StandardCharsets.UTF_8);
    assertThat(source)
        .as("会话页必须从 GET /api/v1/sessions 读列表，详情仍走带会话 ID 的那条路径")
        .contains("get('/api/v1/sessions'")
        .contains("get('/api/v1/sessions/'");
  }

  @Test
  void eachViewReferencesItsEndpoint() throws IOException {
    for (Map.Entry<String, List<String>> entry : VIEW_ENDPOINTS.entrySet()) {
      String source = Files.readString(VIEWS_ROOT.resolve(entry.getKey()), StandardCharsets.UTF_8);
      for (String endpoint : entry.getValue()) {
        assertThat(source).as("%s 应引用端点 %s——页面不是空壳", entry.getKey(), endpoint).contains(endpoint);
      }
    }
  }

  @Test
  void noWriteEntryPointText() throws IOException {
    for (Path file : sourceFiles()) {
      String source = Files.readString(file, StandardCharsets.UTF_8);
      for (String text : WRITE_BUTTON_TEXTS) {
        assertThat(source)
            .as("%s MUST NOT 出现「%s」——界面上不该有假按钮（FR-013）", relative(file), text)
            .doesNotContain(text);
      }
    }
  }

  private static Map<String, List<String>> viewEndpoints() {
    Map<String, List<String>> map = new LinkedHashMap<>();
    map.put(
        "OverviewView.vue",
        List.of("/api/v1/info", "/api/v1/health", "/api/v1/profiles", "/api/v1/tools"));
    map.put("SessionsView.vue", List.of("/api/v1/sessions/"));
    map.put("ProfilesView.vue", List.of("/api/v1/profiles"));
    map.put("ToolsView.vue", List.of("/api/v1/tools"));
    map.put("SchedulesView.vue", List.of("/api/v1/schedules"));
    map.put("MemoryView.vue", List.of("/api/v1/memory"));
    map.put("StatusView.vue", List.of("/api/v1/health", "/api/v1/info"));
    return map;
  }

  /**
   * 判卷范围：`src/` 下的 js/vue/html。
   *
   * <p><b>为什么不含 `vite.config.js`</b>：那里的 `'/api'` 是 dev server 的代理前缀，不是请求路径—— 把它算进来会让「所有请求路径以
   * /api/v1 开头」这条判据误伤构建配置。入口 `index.html` 同样不在扫描范围： 它由 `frontendSourcesExist` 单独判存在性，且它本身不发请求。
   *
   * <p><b>为什么不遍历整个前端目录</b>：`node_modules/` 有上万个文件，走进去再过滤只会拖慢判卷， 且第三方代码本就不在「我们自己写了什么」的判卷范围内。
   */
  private static List<Path> sourceFiles() throws IOException {
    try (Stream<Path> stream = Files.walk(SRC_ROOT)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(AdminConsoleSourceTest::isSourceFile)
          .toList();
    }
  }

  private static boolean isSourceFile(Path file) {
    String path = file.toString();
    return path.endsWith(".js") || path.endsWith(".vue") || path.endsWith(".html");
  }

  private static String relative(Path file) {
    return FRONTEND_ROOT.relativize(file).toString();
  }
}
