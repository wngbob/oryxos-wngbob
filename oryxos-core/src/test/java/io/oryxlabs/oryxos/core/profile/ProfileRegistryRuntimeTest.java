package io.oryxlabs.oryxos.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件 harness「{@code ProfileRegistryRuntimeTest}」的落地（第 29 节，FR-004 / 契约 runtime-registration §2）。
 *
 * <p>判两件事：① 索引从「启动时一次性装载」变成<b>可变并发索引</b>——{@code register} 后立即可见、{@code remove} 后立即消失，快照不可变；②
 * 运行时派生的失败与启动扫描<b>同一异常类型 + 同一消息</b>（SC-003）——这是第 30 节运行时 API 能按类型映射 HTTP 4xx 的前提。
 */
class ProfileRegistryRuntimeTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("ProfileRegistryRuntimeTest：register 后立即可见、remove 后消失且返回 true")
  void register_thenFind_thenRemove() {
    ProfileRegistry registry = new ProfileRegistry(List.of());

    registry.register(profile("recon"));

    assertThat(registry.find("recon")).contains(profile("recon"));
    assertThat(registry.exists("recon")).isTrue();
    assertThat(registry.names()).containsExactly("recon");

    assertThat(registry.remove("recon")).isTrue();

    assertThat(registry.find("recon")).isEmpty();
    assertThat(registry.exists("recon")).isFalse();
    assertThat(registry.names()).isEmpty();
  }

  @Test
  @DisplayName("ProfileRegistryRuntimeTest：remove 不存在的名字返回 false，不抛异常")
  void remove_ofUnknownNameReturnsFalse() {
    ProfileRegistry registry = new ProfileRegistry(List.of(profile("recon")));

    assertThat(registry.remove("no-such-agent")).isFalse();
    assertThat(registry.remove("recon")).isTrue();
    assertThat(registry.remove("recon")).isFalse();
  }

  @Test
  @DisplayName("ProfileRegistryRuntimeTest：同名 register 覆盖旧值")
  void register_sameNameReplaces() {
    ProfileRegistry registry = new ProfileRegistry(List.of());

    registry.register(profile("recon"));
    Profile updated = profileWithDescription("recon", "改过的描述");
    registry.register(updated);

    assertThat(registry.find("recon")).contains(updated);
    assertThat(registry.all()).hasSize(1);
  }

  @Test
  @DisplayName("ProfileRegistryRuntimeTest：all / names 按名排序，且是不可变快照")
  void snapshots_areSortedAndUnmodifiable() {
    ProfileRegistry registry = new ProfileRegistry(List.of(profile("zulu"), profile("alpha")));

    assertThat(registry.names()).containsExactly("alpha", "zulu");
    assertThat(registry.all()).extracting(Profile::name).containsExactly("alpha", "zulu");

    List<Profile> before = registry.all();
    registry.register(profile("mike"));

    // 快照语义：先前拿到的清单不随后续 register 变化
    assertThat(before).extracting(Profile::name).containsExactly("alpha", "zulu");
    assertThat(registry.names()).containsExactly("alpha", "mike", "zulu");

    assertThatThrownBy(() -> registry.names().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> before.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> registry.all().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("ProfileRegistryRuntimeTest：null 入参显式拒绝")
  void nullArguments_areRejected() {
    ProfileRegistry registry = new ProfileRegistry(List.of());

    assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.remove(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.exists(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> registry.find(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("ProfileRegistryRuntimeTest：运行时派生与启动扫描失败时异常类型、消息逐字相同")
  void runtimeDeriveFailure_matchesScanFailure() throws IOException {
    Path agentsDir = tempDir.resolve("agents");
    Path badAgent = agentsDir.resolve("broken");
    Files.createDirectories(badAgent);
    Files.writeString(badAgent.resolve("AGENT.md"), brokenFrontmatter(), StandardCharsets.UTF_8);
    ProfileLoader loader = new ProfileLoader(agentsDir, Set.of("deepseek"), name -> null);

    ProfileLoader.LoadResult scan = loader.loadAllDetailed();

    assertThat(scan.failures()).hasSize(1);
    ProfileLoader.LoadResult.Failure failure = scan.failures().get(0);
    Throwable thrown = catchThrowable(() -> loader.deriveProfile(badAgent));

    assertThat(thrown).isInstanceOf(ProfileLoader.ProfileLoadException.class);
    assertThat(thrown).hasMessage(failure.reason());
  }

  private static String brokenFrontmatter() {
    return """
        ---
        description: 缺 provider 的 Agent
        identity:
          agent_name: 坏掉的
        ---

        正文。
        """;
  }

  private static Profile profile(String name) {
    return profileWithDescription(name, name + " 的描述");
  }

  private static Profile profileWithDescription(String name, String description) {
    return new Profile(
        name,
        description,
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }
}
