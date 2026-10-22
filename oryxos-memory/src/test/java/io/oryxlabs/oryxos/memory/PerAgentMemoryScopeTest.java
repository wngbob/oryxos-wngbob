package io.oryxlabs.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.tool.ToolExecutionContext;
import io.oryxlabs.oryxos.memory.store.MarkdownMemoryStore;
import io.oryxlabs.oryxos.memory.store.MemoryScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * per-agent 长期记忆（第 30 节 FR-007，contracts/workspace-memory.md §4）。
 *
 * <p>守三件事：<b>隔离</b>（A 记的东西 B 看不到）、<b>回退</b>（没有 Agent 上下文时仍是第 22 节的全局单份，既有契约一字未改）、 <b>不缓存路径</b>（同一个
 * store 实例先后服务两个 Agent，各落各的文件）。第三件是最容易写错的一件：把路径在构造器里算一次， 单 Agent 场景全绿、双 Agent 场景把两个 Agent 的记忆写进同一份。
 *
 * <p><b>写侧与读侧的上下文来源不同</b>：写侧由 {@code ToolExecutor} 在调用工具前置入（这里直接调 {@link
 * ToolExecutionContext#callAs} 模拟同一件事），读侧由 {@code MemoryServiceImpl.longTermMemory(Profile)} 按传进来的
 * Profile 置入。两边都判，才知道「工具写的」与「门面读的」指向同一个文件。
 */
class PerAgentMemoryScopeTest {

  private static final String AGENT_A = "weather";

  private static final String AGENT_B = "digest";

  @TempDir Path oryxosDir;

  @AfterEach
  void clearContext() {
    ToolExecutionContext.clear();
  }

  @Test
  @DisplayName("两个 Agent 的 Markdown 记忆互不包含（各写各的 MEMORY.md）")
  void perAgentMarkdownMemoryIsIsolated() throws IOException {
    MemoryService service = new MemoryServiceImpl(new MarkdownMemoryStore(oryxosDir));

    ToolExecutionContext.callAs(AGENT_A, () -> remember(service, "天气 Agent 记住：北京今天晴"));
    ToolExecutionContext.callAs(AGENT_B, () -> remember(service, "日报 Agent 记住：昨天发了 3 条"));

    Path fileA = agentMemory(AGENT_A);
    Path fileB = agentMemory(AGENT_B);
    assertThat(fileA).exists();
    assertThat(fileB).exists();
    assertThat(Files.readString(fileA, StandardCharsets.UTF_8))
        .contains("北京今天晴")
        .doesNotContain("昨天发了 3 条");
    assertThat(Files.readString(fileB, StandardCharsets.UTF_8))
        .contains("昨天发了 3 条")
        .doesNotContain("北京今天晴");

    // 读侧走门面：Profile 名即落点，与写侧同一份文件
    assertThat(service.longTermMemory(profile(AGENT_A)))
        .hasValueSatisfying(t -> assertThat(t).contains("北京今天晴"));
    assertThat(service.longTermMemory(profile(AGENT_B)))
        .hasValueSatisfying(t -> assertThat(t).contains("昨天发了 3 条"));
    // 不存在的 Agent 读到的是自己那份空文件，不是别人的
    assertThat(service.longTermMemory(profile("ghost"))).isEmpty();
  }

  @Test
  @DisplayName("没有 Agent 上下文时回退全局 memory/MEMORY.md（第 22 节行为一字未改）")
  void withoutContextFallsBackToGlobalMemoryFile() throws IOException {
    MemoryService service = new MemoryServiceImpl(new MarkdownMemoryStore(oryxosDir));

    service.remember("全局记住：值班群叫 ops", MemoryScope.CORE);

    Path global = oryxosDir.resolve("memory").resolve("MEMORY.md");
    assertThat(global).exists();
    assertThat(Files.readString(global, StandardCharsets.UTF_8)).contains("值班群叫 ops");
    assertThat(oryxosDir.resolve("agents")).doesNotExist();
    assertThat(service.longTermMemory(null))
        .hasValueSatisfying(t -> assertThat(t).contains("值班群叫 ops"));
  }

  @Test
  @DisplayName("同一个 store 实例先后服务两个 Agent：各落各的文件（路径每次重算、不缓存）")
  void markdownStoreDoesNotCachePathBetweenCalls() throws IOException {
    MarkdownMemoryStore store = new MarkdownMemoryStore(oryxosDir);

    ToolExecutionContext.callAs(AGENT_A, () -> append(store, "第一条"));
    ToolExecutionContext.callAs(AGENT_B, () -> append(store, "第二条"));

    assertThat(Files.readString(agentMemory(AGENT_A), StandardCharsets.UTF_8))
        .contains("第一条")
        .doesNotContain("第二条");
    assertThat(Files.readString(agentMemory(AGENT_B), StandardCharsets.UTF_8))
        .contains("第二条")
        .doesNotContain("第一条");
  }

  private Path agentMemory(String agentName) {
    return oryxosDir.resolve("agents").resolve(agentName).resolve("MEMORY.md");
  }

  /** 返回写进去的正文：{@code callAs} 收的是 {@code Supplier}，故这两个包装顺便让断言能直接复用参数。 */
  private static String remember(MemoryService service, String content) {
    service.remember(content, MemoryScope.ARCHIVAL);
    return content;
  }

  private static String append(MarkdownMemoryStore store, String content) {
    store.append(content, MemoryScope.ARCHIVAL);
    return content;
  }

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec("mock", "mock-model", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }
}
