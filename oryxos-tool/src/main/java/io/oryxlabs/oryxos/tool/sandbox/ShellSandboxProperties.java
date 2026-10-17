package io.oryxlabs.oryxos.tool.sandbox;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可执行文件白名单配置（{@code shell.allowed_commands}，契约 {@code
 * specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md} §3.2）。
 *
 * <p>语义是<b>可执行文件名</b>集合：既不含路径也不含参数。条目写 {@code /bin/ls} 会与校验时的首 token {@code ls} 永不匹配（静默全拒），这条陷阱 在
 * {@link WhitelistSandbox} 的 javadoc 里另有详述。
 *
 * <p>键名逐字是 snake_case、不带 {@code oryxos.} 前缀——技术方案 §6.7 原文即此形态。
 *
 * @param allowedCommands 允许执行的命令名；缺省为空表，空表 = 全拒（不是「不校验」）
 */
@ConfigurationProperties(prefix = "shell")
public record ShellSandboxProperties(List<String> allowedCommands) {

  /**
   * 归一化：配置块整个缺失时绑成 {@code null}，这里归一化为空表。
   *
   * <p>理由同 {@link FileSandboxProperties}——{@code CliTestContext.boot()} 就不给这三块配置，不归一化会在启动期 NPE。
   */
  public ShellSandboxProperties {
    allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
  }
}
