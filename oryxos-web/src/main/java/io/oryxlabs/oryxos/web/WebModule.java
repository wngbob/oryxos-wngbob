package io.oryxlabs.oryxos.web;

/**
 * oryxos-web —— 能力五（Web Service）。
 *
 * <p>规划内容：{@code WebServer}、6 个 {@code ApiController}（Session / Agent / Profile / Memory / Tool /
 * System，只做参数校验与响应包装）、{@code GlobalExceptionHandler}、 统一响应信封 {@code
 * ApiResponse}、OpenAPI（springdoc-openapi 在 US-5 引入）。
 *
 * <p>Maven 骨架占位类，标识模块与包结构；具体实现随 user story 落地（见 CLAUDE.md 实施路线）。
 */
public final class WebModule {

  public static final String NAME = "oryxos-web";

  private WebModule() {}
}
