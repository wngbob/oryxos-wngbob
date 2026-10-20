package io.oryxlabs.oryxos.web.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Web 层配置（第 26 节）：单页应用回落 + 静态资源缓存 + 跨域。
 *
 * <p><b>为什么需要回落</b>：管理台是单页应用，`/admin/sessions` 这类子路由在**浏览器刷新**时会以真实路径打到服务端， 而磁盘上没有这个文件。不回落到
 * `index.html` 就会 404（FR-016 / FR-011）。
 *
 * <p><b>`/api/**` 天然不受影响</b>：回落规则只挂在 `/admin/**` 上，接口路径与它没有交集，不需要额外排除逻辑。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  /** 管理台在容器里的访问路径。 */
  private static final String ADMIN_PATH = "/admin/";

  /** Vite 构建产物在 classpath 上的位置（`build.outDir` 指到这里）。 */
  private static final String ADMIN_LOCATION = "classpath:/static/admin/";

  /** SPA 入口文件名。 */
  private static final String INDEX_HTML = "index.html";

  /** 带内容哈希的静态资源可永久缓存（一年）。 */
  private static final long ASSET_MAX_AGE_DAYS = 365L;

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // / 与 /admin 都跳到 /admin/：前者是「打开根路径就该看到管理台」，后者是补上尾部斜杠，
    // 否则相对路径的静态资源在 /admin 下会解析到根上
    registry.addRedirectViewController("/", ADMIN_PATH);
    registry.addRedirectViewController("/admin", ADMIN_PATH);

    // /admin/ 不能交给资源处理器：`/admin/**` 命中它时剩下的资源路径是空串，而 ResourceHttpRequestHandler
    // 在空路径上直接返回 null（根本进不到解析链，SpaFallbackResolver 也就没机会回落）——实测表现为
    // 「打开 http://host:8080/admin/ 是 404，而 /admin/sessions 正常」。故显式转发到入口文件
    registry.addViewController(ADMIN_PATH).setViewName("forward:" + ADMIN_PATH + INDEX_HTML);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // 先注册更具体的 /admin/assets/**：注册顺序即优先级，长缓存规则必须盖在通用规则之上
    registry
        .addResourceHandler("/admin/assets/**")
        .addResourceLocations(ADMIN_LOCATION + "assets/")
        .setCacheControl(
            CacheControl.maxAge(ASSET_MAX_AGE_DAYS, TimeUnit.DAYS).cachePublic().immutable());

    // 其余 /admin/**：命中就发文件，未命中回落 index.html（刷新子路由不 404）
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations(ADMIN_LOCATION)
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(new SpaFallbackResolver());
  }

  /**
   * 未命中即回落 {@code index.html} 的资源解析器。
   *
   * <p>只用于 `classpath:/static/admin/` 这一处；`/admin/assets/**` 刻意不用它——那里未命中就是真 404，
   * 回落成功只会把「构建产物缺失」伪装成一个能打开的页面。
   */
  private static final class SpaFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource resource = super.getResource(resourcePath, location);
      if (resource != null) {
        return resource;
      }
      // 走一遍父类：它自带「路径越界即拒」的校验，不要绕过它直接 new ClassPathResource
      return super.getResource(INDEX_HTML, location);
    }
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // 核心阶段不设认证、假设内网部署，接口全开放便于调试（FR-023 / 技术方案 §7.4）。
    // allowCredentials(false) 是硬约束：它与通配来源不能并存，Spring 会直接抛异常
    registry
        .addMapping("/api/**")
        .allowedOriginPatterns("*")
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(false)
        .maxAge(3600);
  }
}
