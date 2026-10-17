package io.oryxlabs.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件路径白名单配置（{@code file.allowed_paths}，契约 {@code
 * specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md} §3.2）。
 *
 * <p>语义是<b>目录前缀</b>集合，不是精确文件清单：白名单 {@code /workspace} 放行 {@code /workspace/a/b.txt}。比对由 {@link
 * WhitelistSandbox} 负责，本记录只承载「配置的形状」。
 *
 * <p>键名逐字是 snake_case、不带 {@code oryxos.} 前缀——技术方案 §6.7 原文即此形态。Spring 的 relaxed binding 让 {@code
 * allowed-paths} 等形态也能绑上，但文档与随包默认只认这一种：两处真相会让运维不知道以哪份为准。
 *
 * <p>本记录<b>不做</b>空条目过滤——那是「解释配置」的语义，归 {@link WhitelistSandbox}；把语义塞进值对象会让单测必须绕一层构造才能验过滤。
 *
 * @param allowedPaths 允许访问的目录前缀；缺省为空表，空表 = 全拒（不是「不校验」）
 */
@ConfigurationProperties(prefix = "file")
public record FileSandboxProperties(List<String> allowedPaths) {

  /**
   * 归一化：配置块整个缺失时绑成 {@code null}，这里归一化为空表。
   *
   * <p>不归一化的后果不是「少了个默认值」而是启动期 NPE：{@code CliTestContext.boot()} 就不给这三块配置，{@link WhitelistSandbox}
   * 构造时对 {@code null} 调 {@code List.copyOf} 会当场炸。
   */
  public FileSandboxProperties {
    allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
  }
}
