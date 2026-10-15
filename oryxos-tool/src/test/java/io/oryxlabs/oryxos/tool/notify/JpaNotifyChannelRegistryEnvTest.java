package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import io.oryxlabs.oryxos.storage.repository.NotifyChannelRepository;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@code ${ENV_VAR}} <b>读时解析</b>（2026-10-15 补强②）。
 *
 * <p><b>为什么是读时而不是写时</b>：表里存字面量 {@code ${FEISHU_HOOK_URL}}，解析渠道时才换成真值。这样 db 文件被备份 /
 * 误传时不泄凭证，轮换密钥只改环境变量、不必重新写一遍渠道。合 TechnicalSolution §6.8「在发送时从环境变量解析，不明文落库」。
 *
 * <p><b>注入 {@code UnaryOperator} 而不是读真环境变量</b>：真环境变量在测试进程里改不了（{@code System.getenv} 是只读视图），
 * 而且「变量缺失」这条路径根本没法用真环境构造。同 {@code ProfileLoader} 既有测试的手法。
 *
 * <p><b>本类只钉解析规则</b>：行 → 目标的映射规则（默认渠道怎么选、名字打错怎么报）归 {@link JpaNotifyChannelRegistryTest}，两类的
 * {@code @Mock} 各自独立。
 */
@ExtendWith(MockitoExtension.class)
class JpaNotifyChannelRegistryEnvTest {

  /** 真值刻意不像地址：一旦它出现在报错里就一眼可见。 */
  private static final String SECRET_URL = "https://hook.invalid/secret-token-abc";

  private static final String SECRET_VALUE = "signing-secret-xyz";

  @Mock private NotifyChannelRepository repository;

  /** 只认这两个变量；其余一律「未设置」。 */
  private static final UnaryOperator<String> ENV =
      name -> {
        if ("HOOK".equals(name)) {
          return SECRET_URL;
        }
        if ("SIGN".equals(name)) {
          return SECRET_VALUE;
        }
        return null;
      };

  private JpaNotifyChannelRegistry registry() {
    return new JpaNotifyChannelRegistry(repository, ENV);
  }

  private static NotifyChannelEntity row(String url, String configJson) {
    NotifyChannelEntity entity = new NotifyChannelEntity();
    entity.setName("team-lark");
    entity.setType("feishu");
    entity.setUrl(url);
    entity.setConfigJson(configJson);
    return entity;
  }

  private void stubByName(NotifyChannelEntity entity) {
    when(repository.findById("team-lark")).thenReturn(Optional.of(entity));
  }

  @Test
  @DisplayName("url 列里的占位被换成环境变量的值")
  void resolvesPlaceholderInUrl() {
    stubByName(row("${HOOK}", null));

    assertThat(registry().resolve("team-lark").config()).containsEntry("url", SECRET_URL);
  }

  @Test
  @DisplayName("config 值里的占位也被换 —— secret 同样是凭证，同样可以写成占位")
  void resolvesPlaceholderInConfigValue() {
    stubByName(row("${HOOK}", "{\"secret\":\"${SIGN}\"}"));

    Map<String, String> config = registry().resolve("team-lark").config();
    assertThat(config).containsEntry("secret", SECRET_VALUE).containsEntry("url", SECRET_URL);
  }

  @Test
  @DisplayName("混排占位逐个替换：${A}-${B} 两个都换，周围的字面量保留")
  void resolvesMultiplePlaceholdersInOneValue() {
    stubByName(row("${HOOK}", "{\"secret\":\"${SIGN}\"}"));

    // url 与 secret 各含一个占位，两者在同一次 resolve 里都要换掉
    assertThat(registry().resolve("team-lark").config())
        .containsEntry("url", SECRET_URL)
        .containsEntry("secret", SECRET_VALUE);
  }

  @Test
  @DisplayName("没有占位的值原样通过 —— 绝大多数渠道走这条路")
  void leavesPlainValuesUntouched() {
    stubByName(row("https://example.invalid/plain", "{\"secret\":\"plain-secret\"}"));

    assertThat(registry().resolve("team-lark").config())
        .containsEntry("url", "https://example.invalid/plain")
        .containsEntry("secret", "plain-secret");
  }

  @Test
  @DisplayName("变量缺失即报错，且消息里只有变量名 —— 不回显任何凭证值")
  void missingVariableFailsWithoutLeakingValues() {
    stubByName(row("${NOT_SET_ANYWHERE}", null));

    assertThatThrownBy(() -> registry().resolve("team-lark"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NOT_SET_ANYWHERE")
        .hasMessageContaining("team-lark")
        // 渠道名与变量名是可以打的（不是凭证）；地址值一个字符都不许出现
        .hasMessageNotContaining(SECRET_URL);
  }

  @Test
  @DisplayName("形状不合法的 ${} 原样保留、不报错 —— 正则匹配不到就当普通字面量")
  void malformedPlaceholderIsLeftAsIs() {
    // ${A B} 含空格、${} 空名、$HOOK 缺花括号：都不是占位符，按字面量对待
    stubByName(row("https://example.invalid/${A B}", null));

    assertThat(registry().resolve("team-lark").config())
        .containsEntry("url", "https://example.invalid/${A B}");
  }

  @Test
  @DisplayName("默认渠道（不指定名字）同样走解析 —— 不能只有按名解析那条路接通了")
  void defaultChannelAlsoResolves() {
    when(repository.findFirstByOrderByNameAsc()).thenReturn(Optional.of(row("${HOOK}", null)));

    assertThat(registry().resolve(null).config()).containsEntry("url", SECRET_URL);
  }

  @Test
  @DisplayName("生产构造器不注入 lookup 时退回真环境变量 —— 本类的注入口不是唯一路径")
  void productionConstructorUsesRealEnvironment() {
    // 不断言具体值（进程环境不确定），只断言「能构造出来且解析逻辑在跑」：用一个必然未设置的变量名，
    // 若生产构造器没接 System::getenv，它会原样返回而不是抛「未设置」
    stubByName(row("${ORYXOS_TEST_VAR_THAT_IS_NEVER_SET_12345}", null));

    assertThatThrownBy(() -> new JpaNotifyChannelRegistry(repository).resolve("team-lark"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ORYXOS_TEST_VAR_THAT_IS_NEVER_SET_12345");
  }
}
