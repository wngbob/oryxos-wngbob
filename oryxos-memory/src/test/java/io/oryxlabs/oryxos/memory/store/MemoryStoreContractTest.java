package io.oryxlabs.oryxos.memory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.storage.entity.MemoryEntry;
import io.oryxlabs.oryxos.storage.repository.MemoryEntryRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Pageable;

/**
 * 本节最值钱的一个测试类：<b>同一套断言对三档后端统一跑</b>（FR-024 / SC-003）。
 *
 * <p>任一档破了契约，对应的那一行参数立刻变红，而且从参数名一眼能看出是哪一档破的规矩——这正是第 21 节那道「接口墙」从口号变成事实的证据。
 *
 * <p><b>三档而不是四档</b>：{@code MarkdownMemoryStore} / {@code SqliteMemoryStore} / {@code
 * InMemoryMemoryStore}，第三档即课件的「内存假 Mem0」替身。{@code Mem0MemoryStore} <b>真类不进参数化源</b>——契约测的是「Mem0
 * 这一档有没有守规矩」，真实 REST 报文由 {@code Mem0MemoryStoreTest} 用 {@code MockRestServiceServer} 单独验证；那种 mock
 * 有严格期望序列，不适合当参数。
 *
 * <p><b>SQLite 档在这里挂的是一个内存假 Repository，不是真库</b>——这不是偷懒，是被 JUnit 的求值顺序逼出来的：参数源在 {@code
 * TestTemplateTestDescriptor.prepare()} 阶段求值，而 Spring 的依赖注入发生在更晚的 {@code before()}（建测试实例）阶段，
 * 所以参数源里拿不到 {@code @Autowired} 进来的 Repository（{@code @TempDir} 实例字段踩的是同一个坑，见下）。真实 SQL 的行为
 * （手工脚本建表、{@code LIMIT}、{@code LOWER(...) LIKE LOWER(...)}）由 {@code SqliteMemoryStoreTest} 用
 * {@code @DataJpaTest} 覆盖；本类这一行验的是 {@code SqliteMemoryStore}
 * <b>自己</b>的取法——核心区那条查询根本不带分页参数、归档区那条按上限取、 检索那条不设上限。两者分工，谁也不替代谁。
 *
 * <p><b>为什么每个用例现造一次档</b>：三档里有两档把状态落在文件 / 库上，{@code allStores()} 每次调用都换一个新的工作区根目录、 换一个空的假
 * Repository——否则前一个用例灌进去的 500 条归档会被后一个用例的断言撞上。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryStoreContractTest {

  /** 核心记忆。断言「一字不少」时比对的就是这一串。 */
  private static final String CORE_MEMORY = "用户叫小王，偏好用 Java";

  /** 归档流水的行前缀，后面拼序号。 */
  private static final String ARCHIVE_PREFIX = "归档流水 ";

  /** 只写在最早那一条归档里的记号，用来验「被 load() 裁掉但仍检索得到」。 */
  private static final String EARLY_NEEDLE = "ZZQ-EARLY";

  /**
   * 灌进归档区的条数。
   *
   * <p>500 条 × 每条约 30 字符 ≈ 15000 字符，对 Markdown 档的 4000 字符阈值与另外两档的 100 条上限都远超——故同一条断言对三档都成立。
   */
  private static final int ARCHIVE_ROWS = 500;

  /** 参数源给出的档名，顺序与 {@code allStores()} 一致。 */
  private static final List<String> TIER_NAMES = List.of("markdown", "sqlite", "mem0-替身");

  /**
   * 临时工作区根，由 JUnit 注入并<b>由 JUnit 删除</b>。
   *
   * <p><b>必须是静态字段</b>：{@code @MethodSource} 的参数源在<b>每个用例的 {@code @BeforeEach} 之前</b>求值，而实例级
   * {@code @TempDir} 正是靠 {@code @BeforeEach} 注入的——用实例级会拿到 {@code null}（实测过）。静态的则在
   * {@code @BeforeAll} 阶段就位，参数源读得到。
   */
  @TempDir static Path tempDir;

  /** 每个用例现造一次档，故需要一个递增序号给它们各分一个互不干扰的工作区根目录。 */
  private final AtomicInteger storeSeq = new AtomicInteger();

  /**
   * 契约测试的参数源：三档实现，每档带一个可读的名字（参数名会出现在失败报告里）。
   *
   * @return 三档 {@link LongTermMemoryStore}，每次调用都是全新的实例、新目录、空假 Repository
   */
  Stream<Arguments> allStores() {
    Path root = tempDir.resolve("store-" + storeSeq.incrementAndGet());
    return Stream.of(
        Arguments.of(Named.of(TIER_NAMES.get(0), new MarkdownMemoryStore(root))),
        Arguments.of(Named.of(TIER_NAMES.get(1), new SqliteMemoryStore(inMemoryRepository()))),
        Arguments.of(Named.of(TIER_NAMES.get(2), new InMemoryMemoryStore())));
  }

  /**
   * 参数源的完整性：<b>恰好三档，一档不多一档不少</b>。
   *
   * <p>没有这条，删掉一行参数不会有任何测试变红——参数化测试对「参数少了一个」是天然沉默的，那正是契约测试最容易悄悄退化成 「只测了一档」的地方。
   */
  @Test
  @DisplayName("参数源恰好三档_少一档或多一档都要红")
  void allStoresCoversExactlyThreeTiers() {
    List<String> names =
        allStores().map(arguments -> ((Named<?>) arguments.get()[0]).getName()).toList();

    assertThat(names).containsExactlyElementsOf(TIER_NAMES);
  }

  /**
   * 课件 harness 写出的关键回归点之一：<b>截断只裁归档区，核心记忆一字不能少</b>。
   *
   * <p>三档的实现手段各不相同（裁字符串 / 限制查询条数 / 列表截断），但「核心区永远完整在场」这条底线三档一致——契约二。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("截断只裁归档区_核心记忆一字不能少")
  void truncation_onlyTrimsArchival_coreMemorySurvivesIntact(LongTermMemoryStore memory) {
    memory.append(CORE_MEMORY, MemoryScope.CORE);
    for (int i = 0; i < ARCHIVE_ROWS; i++) {
      memory.append(ARCHIVE_PREFIX + i, MemoryScope.ARCHIVAL);
    }

    String loaded = memory.load();

    // 核心区完整——「始终在场」的底线
    assertThat(loaded).contains(CORE_MEMORY);
    // 归档区最早的被裁掉
    assertThat(loaded).doesNotContain(ARCHIVE_PREFIX + 0);
    // 保留的是最近的
    assertThat(loaded).contains(ARCHIVE_PREFIX + (ARCHIVE_ROWS - 1));
  }

  /**
   * 课件 harness 写出的关键回归点之二：<b>写入后立刻可读，不允许有缓存</b>。
   *
   * <p>任何进程内缓存都会破这条——契约一。长期记忆是「Agent 记完下一轮就要看见」的东西，缓存让它变成「重启才生效」。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("写入后立刻可读_不允许有缓存")
  void append_isImmediatelyVisible_noCaching(LongTermMemoryStore memory) {
    memory.append("刚记的事", MemoryScope.ARCHIVAL);

    // 下一次 load 立即可见
    assertThat(memory.load()).contains("刚记的事");
    // 检索同样立即命中
    assertThat(memory.recallByKeyword("刚记的事")).isNotEmpty();
  }

  /** 契约三：scope 由调用方显式指定，写进哪个区就只在哪个区。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("scope 路由：写核心进核心区、写归档进归档区")
  void append_routesToTheRequestedScope(LongTermMemoryStore memory) {
    memory.append(CORE_MEMORY, MemoryScope.CORE);
    memory.append("随手记的一句", MemoryScope.ARCHIVAL);

    String loaded = memory.load();

    assertThat(loaded).contains(CORE_MEMORY).contains("随手记的一句");
    // 核心区那条 MUST NOT 出现在归档条目视图里，反之亦然
    assertThat(memory.archivalEntries()).noneMatch(entry -> entry.contains(CORE_MEMORY));
  }

  /**
   * 契约四：<b>recall 只搜归档区</b>，核心区不参与检索。
   *
   * <p>核心区本来就被全量注入 system prompt，再让检索命中它，等于同一件事在上下文里出现两遍。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("recall 只搜归档区_核心区那条不参与检索")
  void recall_searchesArchivalOnly(LongTermMemoryStore memory) {
    memory.append(CORE_MEMORY, MemoryScope.CORE);
    memory.append("归档里也提到 Java 的一次排查", MemoryScope.ARCHIVAL);

    List<String> hits = memory.recallByKeyword("Java");

    assertThat(hits).isNotEmpty();
    String joined = String.join("\n", hits);
    assertThat(joined).contains("归档里也提到 Java");
    // 核心区那条（同样含「Java」）MUST NOT 被检索出来
    assertThat(joined).doesNotContain(CORE_MEMORY);
  }

  /**
   * 契约四的余下四条断言（T026）：大小写口径、截断无关、未命中形状、能力声明。
   *
   * <p>前三条都在验同一件事——<b>「检索」和「注入 prompt 的那一份」是两件事</b>。混为一谈的后果是记忆「存了但查不到」， 而这件事在 prompt
   * 里看不出来，只有检索侧能暴露。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("检索大小写不敏感_同一关键词换大小写各命中一次")
  void recall_isCaseInsensitiveAcrossTiers(LongTermMemoryStore memory) {
    memory.append("部署流程走 Jenkins 流水线", MemoryScope.ARCHIVAL);

    // 三档的实现手段不同（大小写归一 / LOWER(...) LIKE LOWER(...) / 外部服务），但口径必须一致
    assertThat(memory.recallByKeyword("jenkins")).isNotEmpty();
    assertThat(memory.recallByKeyword("JENKINS")).isNotEmpty();
    assertThat(memory.recallByKeyword("JeNkInS")).isNotEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("被 load() 裁掉的那条仍能被检索到_截断只决定注入上下文的那一份")
  void recall_stillFindsEntriesThatLoadHasTruncated(LongTermMemoryStore memory) {
    memory.append("最早的一条 " + EARLY_NEEDLE, MemoryScope.ARCHIVAL);
    for (int i = 0; i < ARCHIVE_ROWS; i++) {
      memory.append(ARCHIVE_PREFIX + i, MemoryScope.ARCHIVAL);
    }

    // 它确实被裁掉了……
    assertThat(memory.load()).doesNotContain(EARLY_NEEDLE);
    // ……但仍然检索得到。「存了但查不到」是这条要拦的缺陷
    assertThat(String.join("\n", memory.recallByKeyword(EARLY_NEEDLE))).contains(EARLY_NEEDLE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("未命中返回空列表_不抛异常也不返回 null")
  void recall_missReturnsEmptyList(LongTermMemoryStore memory) {
    memory.append("写过一条与查询无关的", MemoryScope.ARCHIVAL);

    // 未命中是正常结果不是错误：抛异常会让上层把「没查到」当成故障，返回 null 会在调用方炸出 NPE
    assertThat(memory.recallByKeyword("根本不存在的关键词ZQ")).isNotNull().isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("capabilities 对同一实例恒定_且取值在三态之内")
  void capabilities_areConstantAndWithinTheThreeStates(LongTermMemoryStore memory) {
    MemoryCapability declared = memory.capabilities();

    assertThat(memory.capabilities()).isSameAs(declared);
    assertThat(declared)
        .isIn(
            MemoryCapability.KEYWORD, MemoryCapability.HYBRID_BUILTIN, MemoryCapability.DELEGATED);
    // 本节三档都是字面匹配：HYBRID_BUILTIN 无实现方（仲裁 5），替身档也不许自称语义检索
    assertThat(declared).isEqualTo(MemoryCapability.KEYWORD);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allStores")
  @DisplayName("archivalEntries 返回归档区全量_与 load() 的截断无关")
  void archivalEntries_areUnaffectedByLoadTruncation(LongTermMemoryStore memory) {
    memory.append(CORE_MEMORY, MemoryScope.CORE);
    for (int i = 0; i < ARCHIVE_ROWS; i++) {
      memory.append(ARCHIVE_PREFIX + i, MemoryScope.ARCHIVAL);
    }

    List<String> entries = memory.archivalEntries();

    // 全量：load() 只注入最近那一截，条目视图是取数用的，不该跟着一起缩水
    assertThat(entries).hasSize(ARCHIVE_ROWS);
    assertThat(String.join("\n", entries)).contains(ARCHIVE_PREFIX + 0);
    // 核心区那条不进归档条目视图（与上面的 scope 路由断言同一口径，这里在「有截断」的前提下再钉一次）
    assertThat(entries).noneMatch(entry -> entry.contains(CORE_MEMORY));
  }

  /**
   * SQLite 档的假 Repository：行为对齐 {@code MemoryEntryRepository} 上那三条查询的语义，按 id 升序维护一个内存表。
   *
   * <p><b>它只在参数源里用</b>，理由见类 javadoc。它刻意把「分页参数」当回事——归档区那条会读 {@code pageable.getPageSize()} 并真的截断，这样
   * {@code SqliteMemoryStore} 若把上限改大或干脆不传分页，契约二的断言就会红。
   *
   * @return 一个空表起步的假 Repository
   */
  private static MemoryEntryRepository inMemoryRepository() {
    List<MemoryEntry> rows = new ArrayList<>();
    AtomicLong sequence = new AtomicLong();
    MemoryEntryRepository repository = mock(MemoryEntryRepository.class);

    when(repository.save(any(MemoryEntry.class)))
        .thenAnswer(
            invocation -> {
              MemoryEntry entry = invocation.getArgument(0);
              entry.setId(sequence.incrementAndGet());
              rows.add(entry);
              return entry;
            });
    when(repository.findByScopeOrderByIdAsc(anyString()))
        .thenAnswer(invocation -> rowsOfScope(rows, invocation.getArgument(0)));
    when(repository.findByScopeOrderByIdDesc(anyString(), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              List<MemoryEntry> matched =
                  new ArrayList<>(rowsOfScope(rows, invocation.getArgument(0)));
              Collections.reverse(matched);
              Pageable pageable = invocation.getArgument(1);
              return List.copyOf(
                  matched.subList(0, Math.min(pageable.getPageSize(), matched.size())));
            });
    when(repository.searchArchival(anyString()))
        .thenAnswer(
            invocation -> {
              // 显式写 <String>：getArgument 是泛型方法，不写实参类型时编译器会在 String.valueOf 的重载里挑到 char[]
              String needle =
                  invocation.<String>getArgument(0).replace("%", "").toLowerCase(Locale.ROOT);
              return rowsOfScope(rows, "ARCHIVAL").stream()
                  .filter(entry -> entry.getContent().toLowerCase(Locale.ROOT).contains(needle))
                  .toList();
            });
    return repository;
  }

  private static List<MemoryEntry> rowsOfScope(List<MemoryEntry> rows, String scope) {
    return rows.stream().filter(row -> scope.equals(row.getScope())).toList();
  }
}
