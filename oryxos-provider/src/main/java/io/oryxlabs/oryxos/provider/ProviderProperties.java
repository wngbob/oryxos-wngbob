package io.oryxlabs.oryxos.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider 全局层配置（{@code oryxos.providers}，契约见 {@code contracts/provider-config.md}）。
 *
 * <p>全局层回答「这个实例连不连得上某家厂商」，与 Profile 层的「这个 Agent 怎么用」两层分工。两层的唯一耦合点是厂商名： Profile 的 {@code
 * provider.name} 必须能在本清单里找到同名项。
 *
 * <p>凭证以 {@code ${ENV_VAR}} 占位声明，由 Spring 在绑定期从环境变量解析——因此环境变量缺失时启动即失败，且失败消息只含 <b>变量名</b>（形如 {@code
 * Could not resolve placeholder 'DEEPSEEK_API_KEY'}），不会回显任何凭证值。
 *
 * @param providers 厂商清单，缺省为空
 */
@ConfigurationProperties(prefix = "oryxos")
public record ProviderProperties(List<ProviderEntry> providers) {

  /** 本节实现的厂商集合。清单里声明了集合外的厂商名即视为配置错误——显式映射表里没有对应分支，静默忽略会让 Agent 在运行期才炸，不如启动期就报出来。 */
  public static final Set<String> SUPPORTED_PROVIDERS = Set.of("deepseek");

  /** 归一化：清单缺省为空表。 */
  public ProviderProperties {
    providers = providers == null ? List.of() : List.copyOf(providers);
  }

  /**
   * 厂商清单项。
   *
   * @param name 厂商名，清单内唯一；Profile 按此名引用
   * @param apiKey 凭证；{@code application.yaml} 中 MUST 写成 {@code ${ENV_VAR}} 占位，禁止明文
   */
  public record ProviderEntry(String name, String apiKey) {}

  /**
   * 校验全局层配置，不合法即抛异常中断启动（FR-003 / FR-014，SC-003「配置错误 100% 在启动加载期被捕获」）。
   *
   * <p>三项校验：厂商名非空、清单内唯一（显式映射不得有歧义）、厂商名在 {@link #SUPPORTED_PROVIDERS} 内。 报错消息只回显厂商名与环境变量名，MUST NOT
   * 回显任何凭证值。
   *
   * @throws IllegalStateException 任一校验不通过
   */
  public void validate() {
    Set<String> seen = new HashSet<>();
    for (ProviderEntry entry : providers) {
      String name = entry.name();
      if (name == null || name.isBlank()) {
        throw new IllegalStateException("oryxos.providers 中存在未声明 name 的项");
      }
      if (!seen.add(name)) {
        throw new IllegalStateException(
            "oryxos.providers 中厂商名重复: '" + name + "'——显式映射表不得有歧义，请检查配置");
      }
      if (!SUPPORTED_PROVIDERS.contains(name)) {
        throw new IllegalStateException(
            "oryxos.providers 声明了当前实现不支持的厂商: '" + name + "'；本节已实现的厂商为 " + SUPPORTED_PROVIDERS);
      }
      if (entry.apiKey() == null || entry.apiKey().isBlank()) {
        throw new IllegalStateException("厂商 '" + name + "' 的 api-key 为空：请确认对应的凭证环境变量已设置（报错不回显凭证值）");
      }
    }
  }
}
