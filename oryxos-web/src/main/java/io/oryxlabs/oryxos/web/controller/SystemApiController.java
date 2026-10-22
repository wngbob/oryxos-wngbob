package io.oryxlabs.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.provider.ProviderProperties;
import io.oryxlabs.oryxos.tool.registry.ToolRegistry;
import io.oryxlabs.oryxos.web.api.ApiResponse;
import io.oryxlabs.oryxos.web.controller.dto.HealthView;
import io.oryxlabs.oryxos.web.controller.dto.InfoView;
import io.oryxlabs.oryxos.web.health.DatabaseHealthProbe;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态两端点（contracts/rest-endpoints.md §4）。
 *
 * <p><b>{@code status} 只由 db 决定</b>（FR-020）：数据库不通，底座就干不了活；某个 Provider 没配只是那把钥匙不在，
 * 不该让整体健康检查变红——把两者混在一个开关里，监控系统分不清「服务挂了」和「少配了一个厂商」。
 *
 * <p><b>{@code providers} 只表达「配置就绪」，不发真实网络探测</b>（裁决 ⑤）：措辞是「已配置 / 未配置」， 不是「连通 /
 * 不可达」。健康检查说谎比不检查更糟，所以宁可如实只报它真正知道的事。
 */
@RestController
@RequestMapping("/api/v1")
@SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "FindSecBugs 对每个 @RequestMapping 方法报一条 SPRING_ENDPOINT，含义是「这是对外入口、值得人工复核」——"
            + "本类就是第 26 节交付的对外入口本身（FR-001），不是缺陷")
public class SystemApiController {

  /** 健康字面量（已定字面量，契约 §4.1）。 */
  private static final String UP = "UP";

  /** 健康字面量（已定字面量，契约 §4.1）。 */
  private static final String DOWN = "DOWN";

  /** 读不到构建版本时的兜底字面量（契约 §4.2）。 */
  private static final String UNKNOWN_VERSION = "unknown";

  /** 构建期注入版本的资源路径（与 CLI {@code --version} 同一份源）。 */
  private static final String VERSION_RESOURCE = "/oryxos-version.properties";

  /** 上述资源里的版本键名。 */
  private static final String VERSION_PROPERTY = "version";

  /** 毫秒 → 秒。 */
  private static final long MILLIS_PER_SECOND = 1000L;

  private static final Logger log = LoggerFactory.getLogger(SystemApiController.class);

  private final DatabaseHealthProbe databaseHealthProbe;
  private final ProviderProperties providerProperties;
  private final ProfileRegistry profileRegistry;
  private final SessionService sessionService;
  private final ToolRegistry toolRegistry;

  /**
   * @param databaseHealthProbe 数据库真探活
   * @param providerProperties 实例级厂商声明（判「配置就绪」的唯一依据）
   * @param profileRegistry 可用 Agent 数
   * @param sessionService 活跃会话数
   * @param toolRegistry 已注册工具数
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "profileRegistry 是 Spring 注入的协作对象；第 29 节起它是运行时可变索引，本类只查不写，持有是依赖注入本身")
  public SystemApiController(
      DatabaseHealthProbe databaseHealthProbe,
      ProviderProperties providerProperties,
      ProfileRegistry profileRegistry,
      SessionService sessionService,
      ToolRegistry toolRegistry) {
    this.databaseHealthProbe = databaseHealthProbe;
    this.providerProperties = providerProperties;
    this.profileRegistry = profileRegistry;
    this.sessionService = sessionService;
    this.toolRegistry = toolRegistry;
  }

  /**
   * 健康检查。
   *
   * @return 整体状态、数据库状态与各厂商的配置就绪状态
   */
  @GetMapping("/health")
  public ApiResponse<HealthView> health() {
    DatabaseHealthProbe.Level db = databaseHealthProbe.probe();
    Set<String> declared =
        providerProperties.providers().stream()
            .map(ProviderProperties.ProviderEntry::name)
            .collect(Collectors.toUnmodifiableSet());
    Map<String, String> providers = new LinkedHashMap<>();
    // 键集合恒等于已实现厂商集合：既不多报一个「我们不支持的厂商」，也不少报一个「支持但没配的」
    for (String provider : ProviderProperties.SUPPORTED_PROVIDERS) {
      providers.put(provider, declared.contains(provider) ? UP : DOWN);
    }
    String status = db == DatabaseHealthProbe.Level.UP ? UP : DOWN;
    return ApiResponse.ok(new HealthView(status, db.name(), providers));
  }

  /**
   * 运行信息。
   *
   * @return 版本、运行时与四个计数
   */
  @GetMapping("/info")
  public ApiResponse<InfoView> info() {
    long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / MILLIS_PER_SECOND;
    return ApiResponse.ok(
        new InfoView(
            "oryxos",
            resolveVersion(),
            System.getProperty("java.version"),
            // 报的是「可用 Agent 数」：加载失败的那些由 /profiles 的 valid:false 呈现，不在这里混算
            profileRegistry.all().size(),
            sessionService.countActive(),
            toolRegistry.registered().size(),
            uptimeSeconds));
  }

  /**
   * 读构建注入的版本；读不到时回 {@code "unknown"}，不伪造。
   *
   * <p><b>优先读 {@value #VERSION_RESOURCE}</b>：那是 {@code oryxos-cli} 的 Maven 资源过滤产物，也是 {@code oryxos
   * --version} 的同一个源。只读 JAR manifest 不够——本仓没有为模块 jar 打开 {@code
   * addDefaultImplementationEntries}，{@code Package#getImplementationVersion()} 在 fat JAR 里恒为
   * {@code null}（2026-10-22 管理台「vunknown」的根因）。manifest 保留为第二来源，兼容不带宽扫描资源、只打 manifest 的构建方式。
   */
  private static String resolveVersion() {
    String fromResource = readVersionResource();
    if (fromResource != null) {
      return fromResource;
    }
    Package apiPackage = SystemApiController.class.getPackage();
    String version = apiPackage == null ? null : apiPackage.getImplementationVersion();
    return version == null || version.isBlank() ? UNKNOWN_VERSION : version;
  }

  /**
   * 读版本资源；不存在或内容为空返回 {@code null}（不是错误——单模块测试的类路径上没有它）。
   *
   * <p>日志豁免按第 25 节口径就地注解：格式串与路径实参都是常量，唯一可变实参是本地 {@code Properties.load} 的 IOException 消息（读本地
   * classpath 资源，不经过网络 / 模型输入）。
   */
  @SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "常量格式串 + 常量路径；异常消息来自本地资源读取，不是不可信输入，就地豁免比全局 exclude 可审计")
  private static String readVersionResource() {
    try (InputStream in = SystemApiController.class.getResourceAsStream(VERSION_RESOURCE)) {
      if (in == null) {
        return null;
      }
      Properties props = new Properties();
      props.load(in);
      String version = props.getProperty(VERSION_PROPERTY);
      return version == null || version.isBlank() ? null : version;
    } catch (IOException e) {
      // 资源读失败不致命：退回 manifest 判定，再不行回 unknown；日志留痕但不打断 /info
      log.debug("读取 {} 失败，退回 JAR manifest：{}", VERSION_RESOURCE, e.getMessage());
      return null;
    }
  }
}
