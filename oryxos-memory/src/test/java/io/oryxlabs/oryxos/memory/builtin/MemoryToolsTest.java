package io.oryxlabs.oryxos.memory.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.memory.MemoryService;
import io.oryxlabs.oryxos.memory.store.MemoryPartition;
import io.oryxlabs.oryxos.memory.store.MemoryScope;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * {@code MemoryTools} 这一层的职责：<b>把模型递进来的字符串翻译成门面要的领域值，并把「缺省归档」这条规矩兜住</b>。
 *
 * <p>故本类用一个只记录调用的假门面，把 {@code MemoryTools} 自己的行为与后端隔开——真正的读写落点由 {@code MarkdownMemoryStoreTest} /
 * {@code MemoryServiceImplTest} 各自钉。
 *
 * <p><b>为什么 {@code scope} 必须带 {@code @ToolParam(required = false)}</b>：Spring AI 的 {@code
 * JsonSchemaGenerator} 对没有注解的参数一律判为 required，不声明的话模型看到的 Schema 会把 {@code scope} 列进必填， 「不确定就填
 * archival」在模型可见层根本不成立（第 19 节 {@code notify} 的 {@code channel} 踩过同一个坑）。
 */
class MemoryToolsTest {

  /** 只记录调用、不做读写的假门面。 */
  private static final class RecordingMemoryService implements MemoryService {

    private final List<String[]> appends = new ArrayList<>();

    /** 检索收到的关键词，按调用顺序。 */
    private final List<String> recalledKeywords = new ArrayList<>();

    /** 下一次检索返回什么——由用例摆好，本类不做任何匹配逻辑。 */
    private List<String> recallHits = List.of();

    @Override
    public void remember(String content, MemoryScope scope) {
      appends.add(new String[] {content, scope.name()});
    }

    @Override
    public List<String> recall(String keyword) {
      recalledKeywords.add(keyword);
      return recallHits;
    }

    @Override
    public Optional<String> longTermMemory(Profile profile) {
      return Optional.empty();
    }

    @Override
    public MemoryPartition partition() {
      // 本类判的是 save_memory / recall_memory 两个工具，分区读取与它们无关
      return new MemoryPartition("", "", false);
    }
  }

  private final RecordingMemoryService memory = new RecordingMemoryService();

  private final MemoryTools tools = new MemoryTools(memory);

  private static Method saveMethod() throws NoSuchMethodException {
    return MemoryTools.class.getMethod("saveMemory", String.class, String.class);
  }

  private static Method recallMethod() throws NoSuchMethodException {
    return MemoryTools.class.getMethod("recallMemory", String.class);
  }

  @Test
  @DisplayName("scope 缺省（null）写归档区——系统不猜、但缺省值定在归档")
  void saveWithoutScope_defaultsToArchival() {
    ToolResult result = tools.saveMemory("用户偏好用 Java", null);

    assertThat(memory.appends).containsExactly(new String[] {"用户偏好用 Java", "ARCHIVAL"});
    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("已记住");
  }

  @Test
  @DisplayName("scope 传 core / archival（小写）都能解析，且大小写不敏感")
  void saveParsesScopeCaseInsensitively() {
    tools.saveMemory("核心的事", "core");
    tools.saveMemory("归档的事", "ARCHIVAL");

    assertThat(memory.appends)
        .containsExactly(new String[] {"核心的事", "CORE"}, new String[] {"归档的事", "ARCHIVAL"});
  }

  @Test
  @DisplayName("scope 传空白写归档区——与缺省同一条路径")
  void saveWithBlankScope_defaultsToArchival() {
    tools.saveMemory("随手记的", "   ");

    assertThat(memory.appends).containsExactly(new String[] {"随手记的", "ARCHIVAL"});
  }

  @Test
  @DisplayName("scope 是非法值时抛异常并点名合法取值——MUST NOT 静默落归档")
  void saveWithUnknownScope_throwsNamingLegalValues() {
    // "CORE "（带尾空格）是非法值：不做 trim，免得把拼错悄悄变成拼对
    assertThatThrownBy(() -> tools.saveMemory("记一条", "CORE "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CORE")
        .hasMessageContaining("ARCHIVAL");
    // 完全不在取值表里的词同样是非法值，不许当成「未知就归档」放行
    assertThatThrownBy(() -> tools.saveMemory("记一条", "important"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("important");

    // 抛异常才让这次调用在 tool_invocations 里被记成失败并把原因回灌模型
    assertThat(memory.appends).isEmpty();
  }

  @Test
  @DisplayName("工具名显式钉死为 save_memory——模型引用它的唯一凭据，交付后不得改")
  void toolNameIsPinnedExplicitly() throws NoSuchMethodException {
    Tool annotation = saveMethod().getAnnotation(Tool.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.name()).isEqualTo("save_memory");
    assertThat(annotation.description()).isNotBlank();
  }

  @Test
  @DisplayName("scope 参数声明为可选——否则模型可见的 Schema 会把它列进 required")
  void scopeParameterIsDeclaredOptional() throws NoSuchMethodException {
    Parameter[] parameters = saveMethod().getParameters();

    ToolParam content = parameters[0].getAnnotation(ToolParam.class);
    assertThat(content).isNotNull();
    assertThat(content.required()).as("content 必填，不加注解即默认 true").isTrue();

    ToolParam scope = parameters[1].getAnnotation(ToolParam.class);
    assertThat(scope).as("scope 必须有显式的 @ToolParam(required = false)").isNotNull();
    assertThat(scope.required()).isFalse();
    assertThat(scope.description()).isNotBlank();
  }

  @Test
  @DisplayName("命中时返回成功结果，内容是命中条目按行的拼接")
  void recallHits_returnsJoinedEntries() {
    memory.recallHits = List.of("上周排查过部署", "部署脚本在 scripts/ 下");

    ToolResult result = tools.recallMemory("部署");

    assertThat(memory.recalledKeywords).containsExactly("部署");
    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("上周排查过部署\n部署脚本在 scripts/ 下");
  }

  @Test
  @DisplayName("未命中返回「没有找到相关记忆」且不抛异常——查不到是正常结果，不是故障")
  void recallMiss_returnsThePinnedSentenceWithoutThrowing() {
    memory.recallHits = List.of();

    ToolResult result = tools.recallMemory("从没记过的事");

    assertThat(result.success()).as("返回失败会被 tool_invocations 记成一次假故障，还会让模型以为工具坏了").isTrue();
    // 文案逐字钉死（FR-013）：模型据它判断「这件事我没记过」，改一个字都是对外行为变化
    assertThat(result.content()).isEqualTo("没有找到相关记忆");
  }

  @Test
  @DisplayName("工具名显式钉死为 recall_memory——与 save_memory 同一口径")
  void recallToolNameIsPinnedExplicitly() throws NoSuchMethodException {
    Tool annotation = recallMethod().getAnnotation(Tool.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.name()).isEqualTo("recall_memory");
    assertThat(annotation.description()).isNotBlank();
    // keyword 必填：检索没有关键词等于全量返回，不如让 Schema 直接说清
    assertThat(recallMethod().getParameters()[0].getAnnotation(ToolParam.class).required())
        .isTrue();
  }
}
