package io.oryxlabs.oryxos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.context.ContextLoader;
import io.oryxlabs.oryxos.core.engine.PromptBuilder;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.memory.store.LongTermMemoryStore;
import io.oryxlabs.oryxos.memory.store.MarkdownMemoryStore;
import io.oryxlabs.oryxos.memory.store.MemoryCapability;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import io.oryxlabs.oryxos.memory.store.MemoryScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 门面自己的职责：<b>转发 + 按后端能力路由 + 把「还没记过东西」翻译成 {@code Optional.empty()}</b>。 读写落到哪里由后端决定，门面不替它做决定，也不替调用方猜
 * scope。
 *
 * <p>前半段用只记录调用的桩后端驱动——这样三个 {@code capabilities()} 分支都能被单独打到，不必真的凑出三档实现。 后半段换成真后端 + 真 {@link
 * PromptBuilder}：<b>桩能证明门面守规矩，证明不了接缝在真链路上真接得上</b>。
 */
class MemoryServiceImplTest {

  /** 假的 oryxos 目录，给真后端用。 */
  @TempDir Path oryxosDir;

  /** 只记录调用、能力可指定的桩后端。 */
  private static final class StubStore implements LongTermMemoryStore {

    private final MemoryCapability capability;

    private final List<String[]> appends = new ArrayList<>();

    private List<String> hits = List.of();

    private int recallCalls;

    StubStore(MemoryCapability capability) {
      this.capability = capability;
    }

    @Override
    public void append(String content, MemoryScope scope) {
      appends.add(new String[] {content, scope.name()});
    }

    @Override
    public String load() {
      return "";
    }

    @Override
    public List<String> recallByKeyword(String keyword) {
      recallCalls++;
      return hits;
    }

    @Override
    public MemoryCapability capabilities() {
      return capability;
    }

    @Override
    public List<String> archivalEntries() {
      return List.of();
    }

    @Override
    public MemoryPartition partition() {
      return new MemoryPartition("", "", false);
    }
  }

  @Test
  @DisplayName("remember 原样转发给后端，不做任何加工")
  void rememberForwardsToTheStore() {
    StubStore store = new StubStore(MemoryCapability.KEYWORD);
    MemoryService service = new MemoryServiceImpl(store);

    service.remember("用户偏好用 Java", MemoryScope.CORE);

    assertThat(store.appends).containsExactly(new String[] {"用户偏好用 Java", "CORE"});
  }

  @Test
  @DisplayName("remember 拒绝 null scope——缺省归档是 MemoryTools 的事，门面不猜")
  void rememberRejectsNullScope() {
    MemoryService service = new MemoryServiceImpl(new StubStore(MemoryCapability.KEYWORD));

    assertThatThrownBy(() -> service.remember("一条", null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("KEYWORD 档：recall 走后端的关键词检索")
  void recallRoutesKeywordCapabilityToKeywordSearch() {
    StubStore store = new StubStore(MemoryCapability.KEYWORD);
    store.hits = List.of("- [2026-10-15] 用户偏好用 Java");
    MemoryService service = new MemoryServiceImpl(store);

    assertThat(service.recall("Java")).containsExactly("- [2026-10-15] 用户偏好用 Java");
    assertThat(store.recallCalls).isEqualTo(1);
  }

  @Test
  @DisplayName("DELEGATED 档：recall 仍走 recallByKeyword（由后端自己转发给外部服务）")
  void recallRoutesDelegatedCapabilityToTheSameCall() {
    StubStore store = new StubStore(MemoryCapability.DELEGATED);
    store.hits = List.of("Mem0 返回的一条");
    MemoryService service = new MemoryServiceImpl(store);

    assertThat(service.recall("Java")).containsExactly("Mem0 返回的一条");
    assertThat(store.recallCalls).isEqualTo(1);
  }

  @Test
  @DisplayName("HYBRID_BUILTIN 档：抛异常点名核心阶段无实现方——MUST NOT 静默降级成关键词检索")
  void recallRefusesHybridBuiltinInsteadOfSilentlyDowngrading() {
    StubStore store = new StubStore(MemoryCapability.HYBRID_BUILTIN);
    MemoryService service = new MemoryServiceImpl(store);

    assertThatThrownBy(() -> service.recall("Java"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HYBRID_BUILTIN")
        // 报错要能自解释：点名「核心阶段无实现方」+ 说清它需要什么 + 给出可行出路
        .hasMessageContaining("核心阶段")
        .hasMessageContaining("内置向量检索路径")
        .hasMessageContaining("Mem0");

    // 一次检索都不许发出去：静默降级会让运维以为「语义检索已生效」
    assertThat(store.recallCalls).isZero();
  }

  @Test
  @DisplayName("KEYWORD / DELEGATED 两档都不抛——只有 HYBRID_BUILTIN 是核心阶段的禁区")
  void recallOnlyRefusesTheUnimplementedCapability() {
    // 这两条与上面那条一起构成三态全覆盖：能力声明是路由的唯一依据，门面不按实现类猜
    for (MemoryCapability capability :
        List.of(MemoryCapability.KEYWORD, MemoryCapability.DELEGATED)) {
      StubStore store = new StubStore(capability);
      store.hits = List.of("一条");
      MemoryService service = new MemoryServiceImpl(store);

      assertThat(service.recall("Java"))
          .as("capability=%s 时应正常返回", capability)
          .containsExactly("一条");
      assertThat(store.recallCalls).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("还没记过任何东西时 longTermMemory 返回 empty——三种「空」都算")
  void longTermMemoryIsEmptyWhenNothingWasEverRemembered() throws IOException {
    // ① 文件压根不存在
    assertThat(serviceOverFreshDir().longTermMemory(profile())).isEmpty();

    // ② 文件在、两个 header 都在，但一条记忆也没有（`oryxos init` 刚建出来的样子）
    Files.createDirectories(oryxosDir.resolve("memory"));
    Files.writeString(
        oryxosDir.resolve("memory").resolve("MEMORY.md"),
        "## 核心记忆\n\n## 归档记忆\n",
        StandardCharsets.UTF_8);
    assertThat(serviceOverFreshDir().longTermMemory(profile())).isEmpty();

    // ③ 文件在、但两个 header 都缺——那是人写的笔记，不是记忆
    Files.writeString(
        oryxosDir.resolve("memory").resolve("MEMORY.md"),
        "# 我的笔记\n\n- 随手写的一行\n",
        StandardCharsets.UTF_8);
    assertThat(serviceOverFreshDir().longTermMemory(profile())).isEmpty();
  }

  @Test
  @DisplayName("记过东西后 longTermMemory 非空且含核心区内容")
  void longTermMemoryCarriesCoreSectionOnceSomethingWasRemembered() {
    MemoryService service = serviceOverFreshDir();
    service.remember("用户叫小王，偏好用 Java", MemoryScope.CORE);

    String memory = service.longTermMemory(profile()).orElseThrow();

    assertThat(memory).contains("用户叫小王，偏好用 Java");
  }

  @Test
  @DisplayName("接缝在真链路上接得上：长期记忆是独立的第二条 system 消息，且不混入会话历史")
  void realSeamFeedsPromptBuilderAsASeparateSystemMessage() {
    MemoryService service = serviceOverFreshDir();
    Session session = Session.create("cli:local:demo", "cli", "local", "demo");
    session.appendUserMessage("你记得我是谁吗？");
    PromptBuilder builder = builderOver(service);

    // 还没记过东西：prompt 里只有第 0 条 system prompt + 会话历史，长期记忆整条不出现（不塞占位文本）
    List<Message> before = builder.build(session, profile()).getInstructions();
    assertThat(before).hasSize(2);
    assertThat(before.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(before.get(1)).isInstanceOf(UserMessage.class);

    service.remember("用户叫小王", MemoryScope.CORE);

    List<Message> instructions = builderOver(service).build(session, profile()).getInstructions();
    assertThat(instructions.get(0)).isInstanceOf(SystemMessage.class);
    assertThat(instructions.get(1)).isInstanceOf(SystemMessage.class);
    String memoryPart = ((SystemMessage) instructions.get(1)).getText();
    assertThat(memoryPart).contains("用户叫小王");
    // FR-015：长期记忆与会话历史是两件事，长期记忆那一条里 MUST NOT 出现对话内容
    assertThat(memoryPart).doesNotContain("你记得我是谁吗");
    // 会话历史排在长期记忆之后
    assertThat(instructions.get(2)).isInstanceOf(UserMessage.class);
  }

  @Test
  @DisplayName("跨会话：换一个全新 Session 组装，长期记忆照旧出现")
  void longTermMemorySurvivesAcrossSessions() {
    MemoryService service = serviceOverFreshDir();
    service.remember("用户叫小王", MemoryScope.CORE);

    Session another = Session.create("cli:local:demo#2", "cli", "local", "demo");
    another.appendUserMessage("我们以前聊过吗？");

    List<Message> instructions = builderOver(service).build(another, profile()).getInstructions();

    assertThat(((SystemMessage) instructions.get(1)).getText()).contains("用户叫小王");
  }

  private MemoryService serviceOverFreshDir() {
    return new MemoryServiceImpl(new MarkdownMemoryStore(oryxosDir));
  }

  /** 真 {@link PromptBuilder} + 真门面：桩能证明门面守规矩，证明不了接缝在真链路上接得上。 */
  private static PromptBuilder builderOver(MemoryService service) {
    ContextLoader contextLoader = mock(ContextLoader.class);
    when(contextLoader.load(any())).thenReturn("");
    ToolCatalog toolCatalog = mock(ToolCatalog.class);
    when(toolCatalog.availableFor(any())).thenReturn(List.of());
    return new PromptBuilder(contextLoader, service, toolCatalog);
  }

  private static Profile profile() {
    return new Profile(
        "demo",
        null,
        new Profile.Identity("demo", "你是演示助手。"),
        new Profile.ProviderSpec("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(null, 20));
  }
}
