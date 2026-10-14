package io.oryxlabs.oryxos.tool.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import io.oryxlabs.oryxos.storage.repository.NotifyChannelRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 行 → 通知目标的映射：data-model.md §5 六步逐条覆盖。
 *
 * <p><b>Mock 掉 Repository、不连库</b>：本类要证的是<b>映射规则</b>（默认渠道怎么选、名字打错怎么报、{@code config} 什么形状），
 * 与「表能不能存」无关——那是 {@code NotifyChannelRepositoryTest} 的活。用替身才能把每一行输入单独摆出来。
 *
 * <p><b>三条最容易写错的口径</b>：① 名字打错 MUST NOT 静默退回默认渠道 ② {@code url} 键恒定存在且以 {@code url} 列为准 ③ {@code
 * config_json} 的值非字符串即报错，不静默转字符串。
 */
@ExtendWith(MockitoExtension.class)
class JpaNotifyChannelRegistryTest {

  private static final String SAMPLE_URL = "https://example.invalid/webhook/team-lark";

  @Mock private NotifyChannelRepository repository;

  private JpaNotifyChannelRegistry registry() {
    return new JpaNotifyChannelRegistry(repository);
  }

  private static NotifyChannelEntity row(String name, String type, String url, String configJson) {
    NotifyChannelEntity entity = new NotifyChannelEntity();
    entity.setName(name);
    entity.setType(type);
    entity.setUrl(url);
    entity.setConfigJson(configJson);
    return entity;
  }

  @Test
  @DisplayName("渠道名缺省（null / 空串 / 纯空白）时取默认渠道：按注册名升序的第一个")
  void blankChannelNameFallsBackToDefault() {
    when(repository.findFirstByOrderByNameAsc())
        .thenReturn(Optional.of(row("alpha-webhook", "webhook", SAMPLE_URL, "{}")));

    assertThat(registry().resolve(null).channelType()).isEqualTo("webhook");
    assertThat(registry().resolve("").config()).containsEntry("url", SAMPLE_URL);
    assertThat(registry().resolve("   ").config()).containsEntry("url", SAMPLE_URL);
  }

  @Test
  @DisplayName("一个渠道都没配时明确报错：文案说清是「未配置任何通知渠道」")
  void emptyRegistryFailsWithClearMessage() {
    when(repository.findFirstByOrderByNameAsc()).thenReturn(Optional.empty());

    assertThatThrownBy(() -> registry().resolve(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未配置任何通知渠道");
  }

  @Test
  @DisplayName("给了渠道名就按名解析，不走默认渠道")
  void resolvesByName() {
    when(repository.findById("team-lark"))
        .thenReturn(Optional.of(row("team-lark", "webhook", SAMPLE_URL, "{\"msgtype\":\"text\"}")));

    NotifyTarget target = registry().resolve("team-lark");

    assertThat(target.channelType()).isEqualTo("webhook");
    assertThat(target.config()).containsEntry("msgtype", "text");
    verify(repository, never()).findFirstByOrderByNameAsc();
  }

  @Test
  @DisplayName("渠道名打错时明确报错，且不静默退回默认渠道")
  void unknownChannelNameFailsWithoutFallingBackToDefault() {
    when(repository.findById("typo-lark")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> registry().resolve("typo-lark"))
        .isInstanceOf(IllegalStateException.class)
        // 报错要带名字：模型才知道自己写错了哪个字
        .hasMessageContaining("typo-lark");
    // 「打错名字」与「一个都没配」是两条不同的报错，后者不该被走到
    verify(repository, never()).findFirstByOrderByNameAsc();
  }

  @Test
  @DisplayName("config 恒定含 url 键，取值来自 url 列而不是 config_json")
  void configAlwaysCarriesUrlKeyFromColumn() {
    when(repository.findById("team-lark"))
        .thenReturn(
            Optional.of(
                row(
                    "team-lark",
                    "webhook",
                    SAMPLE_URL,
                    "{\"url\":\"https://wrong.invalid/hook\",\"msgtype\":\"text\"}")));

    NotifyTarget target = registry().resolve("team-lark");

    // 独立列是权威：config_json 里的同名键被覆盖
    assertThat(target.config()).containsEntry("url", SAMPLE_URL);
    assertThat(target.config()).containsEntry("msgtype", "text");
  }

  @Test
  @DisplayName("url 列为空时 url 键仍是空串，而不是键不存在")
  void blankUrlColumnStillYieldsTheKey() {
    when(repository.findById("team-lark"))
        .thenReturn(Optional.of(row("team-lark", "webhook", null, null)));

    NotifyTarget target = registry().resolve("team-lark");

    // 键恒定存在，适配器只需判「值是不是空白」一种形状
    assertThat(target.config()).containsEntry("url", "");
  }

  @Test
  @DisplayName("config_json 为空或空串时得到空配置，不报错")
  void emptyConfigJsonYieldsEmptyConfig() {
    when(repository.findById("a")).thenReturn(Optional.of(row("a", "webhook", SAMPLE_URL, null)));
    when(repository.findById("b")).thenReturn(Optional.of(row("b", "webhook", SAMPLE_URL, "   ")));

    assertThat(registry().resolve("a").config()).containsOnlyKeys("url");
    assertThat(registry().resolve("b").config()).containsOnlyKeys("url");
  }

  @Test
  @DisplayName("config_json 不是 JSON 对象时明确报错，不静默当作空配置")
  void rejectsNonObjectConfigJson() {
    when(repository.findById("array"))
        .thenReturn(Optional.of(row("array", "webhook", SAMPLE_URL, "[]")));
    when(repository.findById("broken"))
        .thenReturn(Optional.of(row("broken", "webhook", SAMPLE_URL, "{oops")));

    assertThatThrownBy(() -> registry().resolve("array"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry().resolve("broken"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("config_json 的值不是字符串时明确报错，不静默转字符串")
  void rejectsNonStringConfigValues() {
    when(repository.findById("team-lark"))
        .thenReturn(Optional.of(row("team-lark", "webhook", SAMPLE_URL, "{\"retries\":3}")));

    assertThatThrownBy(() -> registry().resolve("team-lark"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retries");
  }

  @Test
  @DisplayName("type 为空白时明确报错：渠道类型定不下来")
  void rejectsBlankType() {
    when(repository.findById("a")).thenReturn(Optional.of(row("a", null, SAMPLE_URL, "{}")));
    when(repository.findById("b")).thenReturn(Optional.of(row("b", "   ", SAMPLE_URL, "{}")));

    assertThatThrownBy(() -> registry().resolve("a")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry().resolve("b")).isInstanceOf(IllegalArgumentException.class);
  }
}
