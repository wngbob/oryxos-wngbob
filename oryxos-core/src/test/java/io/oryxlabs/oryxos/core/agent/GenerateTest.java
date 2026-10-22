package io.oryxlabs.oryxos.core.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.llm.LlmClient;
import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.profile.ProfileLoader;
import io.oryxlabs.oryxos.core.profile.ProfileRegistry;
import io.oryxlabs.oryxos.core.schedule.AgentScheduler;
import io.oryxlabs.oryxos.core.session.SessionService;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 课件 harness 第 2 行「{@code GenerateTest}」的落地（第 30 节，就地映射到 {@code generate-files}）。
 *
 * <p>守三件事：<b>草稿必须过既有的解析校验</b>（生成端不是第二套规则，{@link ProfileLoader#parse} 说什么就是什么）、<b>只生成不落盘不注册</b>
 * （预览阶段的产出不许有副作用，否则「取消」二字就没有意义）、<b>作者模型没配时一次调用都不发</b>（{@code model=null} 打给 OpenAI
 * 兼容端点会变成一条难查的对端报错）。
 *
 * <p><b>这里用的是真的 {@link ProfileLoader}</b>，不是替身：断言「产出能被既有解析器解析」如果解析器自己也是假的，那条断言就等于
 * 「我写的草稿能被我自己写死的东西接受」。
 */
class GenerateTest {

  /** 解析用的工作区目录；本类的服务只解析文本，不读目录。 */
  private static final Path WORKSPACE = Path.of("/tmp/oryxos-l30-generate/.oryxos");

  private static final String AGENT = "weather";

  private static final String DESCRIPTION = "每天早上九点查北京天气，把穿搭建议发到群里";

  private final ProfileRegistry registry = mock(ProfileRegistry.class);
  private final AgentScheduler scheduler = mock(AgentScheduler.class);
  private final AgentStore store = mock(AgentStore.class);
  private final LlmClient llmClient = mock(LlmClient.class);
  private final ToolCatalog toolCatalog = mock(ToolCatalog.class);
  private final SessionService sessionService = mock(SessionService.class);
  private final AgentService agentService = mock(AgentService.class);

  /** 真解析器：厂商清单里同时有 deepseek 与 mock，故草稿写哪家都能解析，改写与否由断言自己看。 */
  private final ProfileLoader profileLoader =
      new ProfileLoader(WORKSPACE.resolve("agents"), Set.of("deepseek", "mock"), name -> null);

  private final AgentLifecycleService service =
      new AgentLifecycleService(
          profileLoader,
          registry,
          scheduler,
          store,
          llmClient,
          toolCatalog,
          sessionService,
          agentService,
          List.of("deepseek", "mock"),
          "deepseek",
          "deepseek-chat");

  @Test
  @DisplayName("GenerateTest：产出的 AGENT.md 能被既有 ProfileLoader 解析（且 provider 沿用该 Agent 现有的）")
  void generatedDraftParsesWithExistingLoader() {
    givenExistingAgent("mock", "mock-model");
    when(toolCatalog.find("read_file")).thenReturn(Optional.of(mock(OryxTool.class)));
    // 模型自己会写它以为对的厂商——契约要求改写成本仓这个 Agent 真正在用的那家
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(response(draft("deepseek", "deepseek-chat")));

    Map<String, String> files = service.generateFiles(AGENT, DESCRIPTION);

    assertThat(files).containsOnlyKeys("AGENT.md");
    String text = files.get("AGENT.md");
    assertThat(text).startsWith("---");
    Profile parsed = profileLoader.parse(text, AGENT);
    assertThat(parsed.provider().name()).isEqualTo("mock");
    assertThat(parsed.provider().model()).isEqualTo("mock-model");
    assertThat(parsed.description()).isEqualTo("每天早上九点查北京天气");
    assertThat(parsed.tools()).containsExactly("read_file");
  }

  @Test
  @DisplayName("GenerateTest：只生成不落盘、不注册（预览阶段的产物没有副作用）")
  void generationDoesNotWriteOrRegister() {
    givenExistingAgent("mock", "mock-model");
    when(toolCatalog.find("read_file")).thenReturn(Optional.of(mock(OryxTool.class)));
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(response(draft("deepseek", "deepseek-chat")));

    service.generateFiles(AGENT, DESCRIPTION);

    verify(store, never()).writeAll(any(), any());
    verify(store, never()).writeAgentMarkdown(any(), any());
    verify(store, never()).scaffold(any(), any(), any(), any());
    verify(store, never()).archive(any());
    verify(registry, never()).register(any());
    verify(registry, never()).remove(any());
    verify(scheduler, never()).registerProfile(any());
    verify(scheduler, never()).unregisterProfile(any());
  }

  @Test
  @DisplayName("GenerateTest：模型多吐的代码围栏被剥掉")
  void codeFencesAreStripped() {
    givenExistingAgent("mock", "mock-model");
    when(toolCatalog.find("read_file")).thenReturn(Optional.of(mock(OryxTool.class)));
    String fenced = "```markdown\n" + draft("deepseek", "deepseek-chat").strip() + "\n```";
    when(llmClient.chat(anyString(), any(), any())).thenReturn(response(fenced));

    String text = service.generateFiles(AGENT, DESCRIPTION).get("AGENT.md");

    assertThat(text).startsWith("---");
    assertThat(text).doesNotContain("```");
    assertThat(text.strip()).endsWith("到点把结论推给值班群。");
  }

  @Test
  @DisplayName("GenerateTest：模型产出不是合法 AGENT.md 时 400 带可读原因")
  void invalidGeneratedMarkdownIsRejectedWithReadableReason() {
    givenExistingAgent("mock", "mock-model");
    when(llmClient.chat(anyString(), any(), any())).thenReturn(response("抱歉，我无法生成这个文件。"));

    assertThatThrownBy(() -> service.generateFiles(AGENT, DESCRIPTION))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("生成的 AGENT.md 无法解析")
        .hasMessageContaining("frontmatter 缺失");
  }

  @Test
  @DisplayName("GenerateTest：作者模型空 503（一次模型调用都不发，更不会发 model=null）")
  void blankAuthorModelThrowsAuthorUnavailable() {
    givenExistingAgent("mock", "mock-model");
    AgentLifecycleService withoutAuthorModel =
        new AgentLifecycleService(
            profileLoader,
            registry,
            scheduler,
            store,
            llmClient,
            toolCatalog,
            sessionService,
            agentService,
            List.of("deepseek", "mock"),
            "deepseek",
            "");

    assertThatThrownBy(() -> withoutAuthorModel.generateFiles(AGENT, DESCRIPTION))
        .isInstanceOf(AgentAuthorUnavailableException.class)
        .hasMessageContaining("oryxos.author.model");

    verifyNoInteractions(llmClient);
  }

  @Test
  @DisplayName("GenerateTest：调用方用作者配置（作者 provider/model），审计 sessionId 以 agent-author: 开头")
  void generationUsesAuthorConfigAsCaller() {
    givenExistingAgent("mock", "mock-model");
    when(toolCatalog.find("read_file")).thenReturn(Optional.of(mock(OryxTool.class)));
    when(llmClient.chat(anyString(), any(), any()))
        .thenReturn(response(draft("deepseek", "deepseek-chat")));

    service.generateFiles(AGENT, DESCRIPTION);

    ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Profile> caller = ArgumentCaptor.forClass(Profile.class);
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(llmClient).chat(sessionId.capture(), caller.capture(), prompt.capture());
    assertThat(sessionId.getValue()).startsWith("agent-author:");
    // 调用方必须跟随作者配置：跟随该 Agent（mock）会打到固定脚本，生成不出任何东西
    assertThat(caller.getValue().provider().name()).isEqualTo("deepseek");
    assertThat(caller.getValue().provider().model()).isEqualTo("deepseek-chat");
    assertThat(prompt.getValue().getContents()).contains(DESCRIPTION);
    assertThat(prompt.getValue().getContents()).contains("OryxOS 的 Agent 文件生成器");
  }

  @Test
  @DisplayName("GenerateTest：提示词常量与 docs/prompt/prompt.md 逐字一致（改一边不改另一边，构建就红）")
  void promptDocumentMatchesConstant() throws IOException {
    Path document = repoRoot().resolve("docs/prompt/prompt.md");
    assertThat(Files.isRegularFile(document)).as("提示词文档不存在: %s", document).isTrue();
    String raw = Files.readString(document, StandardCharsets.UTF_8);

    // 文档给人读，第一行是标题；常量是运行时那一份，只含正文
    String body = raw;
    if (body.startsWith("# AGENT_AUTHOR_PROMPT")) {
      body = body.substring("# AGENT_AUTHOR_PROMPT".length());
    }

    assertThat(AgentAuthorPrompt.PROMPT.strip()).isEqualTo(body.strip());
  }

  // ---------- 夹具 ----------

  private void givenExistingAgent(String providerName, String model) {
    when(registry.find(AGENT)).thenReturn(Optional.of(existing(providerName, model)));
  }

  private static Profile existing(String providerName, String model) {
    return new Profile(
        AGENT,
        "每天查天气",
        Profile.Identity.EMPTY,
        new Profile.ProviderSpec(providerName, model, null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  /** 模型可能吐出的那种草稿：frontmatter + 正文，厂商由模型自己选。 */
  private static String draft(String providerName, String model) {
    return """
        ---
        description: 每天早上九点查北京天气
        provider:
          name: %s
          model: %s
        tools: [read_file]
        schedules:
          - cron: "0 9 * * *"
            zone: Asia/Shanghai
            message: 查今天的天气
        ---

        # 天气播报员

        ## 任务

        先读 REFERENCE.md，再查天气，最后到点把结论推给值班群。
        """
        .formatted(providerName, model);
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(
        List.of(new Generation(new AssistantMessage(text))),
        ChatResponseMetadata.builder().build());
  }

  /** 从当前工作目录（surefire 的 cwd 是模块目录）往上找仓库根。找不到就直接失败，不静默跳过。 */
  private static Path repoRoot() {
    try (Stream<Path> candidates =
        Stream.iterate(Path.of("").toAbsolutePath(), Objects::nonNull, Path::getParent)) {
      return candidates
          .filter(
              dir ->
                  Files.isDirectory(dir.resolve(".git"))
                      && Files.isDirectory(dir.resolve("oryxos-boot")))
          .findFirst()
          .orElseThrow(
              () -> new IllegalStateException("从 " + Path.of("").toAbsolutePath() + " 往上找不到仓库根"));
    }
  }
}
