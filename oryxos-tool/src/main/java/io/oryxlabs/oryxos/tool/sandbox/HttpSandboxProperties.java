package io.oryxlabs.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP 域名白名单配置（{@code http.allowed_domains}，契约 {@code
 * specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md} §3.2）。
 *
 * <p>条目可以是精确域名（{@code api.example.com}）或 {@code *.} 通配模式（{@code *.example.com}，只覆盖子域、不覆盖裸域）。
 *
 * <p>键名逐字是 snake_case、不带 {@code oryxos.} 前缀——技术方案 §6.7 原文即此形态。域名不是凭证，本键不含任何 token。
 *
 * @param allowedDomains 允许访问的域名或 {@code *.} 通配模式；缺省为空表，空表 = 全拒（不是「不校验」）
 */
@ConfigurationProperties(prefix = "http")
public record HttpSandboxProperties(List<String> allowedDomains) {

  /**
   * 归一化：配置块整个缺失时绑成 {@code null}，这里归一化为空表。
   *
   * <p>理由同 {@link FileSandboxProperties}——{@code CliTestContext.boot()} 就不给这三块配置，不归一化会在启动期 NPE。
   */
  public HttpSandboxProperties {
    allowedDomains = allowedDomains == null ? List.of() : List.copyOf(allowedDomains);
  }
}
