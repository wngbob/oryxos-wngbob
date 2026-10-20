package io.oryxlabs.oryxos.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.storage.entity.SessionEntity;
import io.oryxlabs.oryxos.storage.repository.SessionRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * {@link SessionService} 的验收测试（课件第 18 节「四、验收 harness」）。
 *
 * <p>守护的三条：同一三元组历次取会话都是同一个（幂等，FR-007）；三元组任一不同即不同会话（隔离）；会话标识的拼接只存在 唯一一处（FR-006）。
 *
 * <p><b>为什么用 Map 支撑的 Repository 替身而不是内存库</b>：本类要测的是「取或建」的判定与标识拼接，与 SQL 无关；真 SQLite 的建表 / 存取 / 落盘归
 * {@code SessionRepositoryTest}（US2）。两处职责分开，红了才知道该修哪一层。
 *
 * <p>全程替身，不碰网络、不碰数据库。
 */
class SessionServiceTest {

  /** 替身背后的一行行数据，键是 {@code session_id}。 */
  private final Map<String, SessionEntity> rows = new LinkedHashMap<>();

  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    SessionRepository repository = mock(SessionRepository.class);
    when(repository.findById(anyString()))
        .thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
    when(repository.save(any(SessionEntity.class)))
        .thenAnswer(
            call -> {
              SessionEntity entity = call.getArgument(0, SessionEntity.class);
              rows.put(entity.getSessionId(), entity);
              return entity;
            });
    // 第 26 节新增的派生查询：数的是替身背后那几行的真实状态，而不是写死一个常量
    when(repository.countByStatus(anyString()))
        .thenAnswer(
            call -> {
              String status = call.getArgument(0, String.class);
              return rows.values().stream().filter(row -> status.equals(row.getStatus())).count();
            });
    sessionService = new JpaSessionService(repository);
  }

  @Test
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  void sameTriple_getOrCreateAlwaysReturnsSameSession() {
    var first = sessionService.getOrCreate("cli", "wang", "default");
    var second = sessionService.getOrCreate("cli", "wang", "default");
    assertEquals(first.id(), second.id()); // 幂等：多轮对话靠它串起来

    var other = sessionService.getOrCreate("web", "wang", "default");
    assertNotEquals(first.id(), other.id()); // channel 不同就是不同会话
  }

  @Test
  @DisplayName("三元组任一不同即不同会话：channel / user / profile 各改一处")
  void anyDifferentSegmentYieldsDifferentSession() {
    String base = sessionService.getOrCreate("cli", "wang", "default").id();

    assertThat(sessionService.getOrCreate("web", "wang", "default").id()).isNotEqualTo(base);
    assertThat(sessionService.getOrCreate("cli", "li", "default").id()).isNotEqualTo(base);
    assertThat(sessionService.getOrCreate("cli", "wang", "weather").id()).isNotEqualTo(base);
  }

  @Test
  @DisplayName("会话标识的拼接只此一处：形如 channel:user:profile，三段逐字来自入参")
  void sessionIdIsJoinedFromTripleInOnePlace() {
    Session session = sessionService.getOrCreate("cli", "wang", "default");

    // 断言的是「格式」，格式的真相源只有 SessionId.of 一处；任何第二处自己拼字符串、
    // 哪怕只差一个分隔符，产出的标识都会在这里对不上
    assertThat(session.id()).isEqualTo("cli:wang:default");
    assertThat(session.channel()).isEqualTo("cli");
    assertThat(session.userId()).isEqualTo("wang");
    assertThat(session.profileName()).isEqualTo("default");
  }

  @Test
  @DisplayName("已落库的会话：再取回来带历史，仍是同一个")
  void existingSessionIsRestoredWithHistory() {
    Session created = sessionService.getOrCreate("cli", "wang", "default");
    created.appendUserMessage("今天天气怎么样？");
    created.appendAssistantMessage("晴，26 度。", List.of());
    sessionService.save(created);

    Session reloaded = sessionService.getOrCreate("cli", "wang", "default");

    assertThat(reloaded.id()).isEqualTo(created.id());
    assertThat(reloaded.messages()).hasSize(2);
  }

  @Test
  @DisplayName("未落库的会话：历次 getOrCreate 拿到的是空会话，不会凭空长出历史")
  void unsavedSessionStartsEmpty() {
    Session first = sessionService.getOrCreate("cli", "wang", "default");
    first.appendUserMessage("只在内存里说了一句");

    Session second = sessionService.getOrCreate("cli", "wang", "default");

    assertThat(second.messages()).isEmpty();
  }

  @Test
  @DisplayName("按标识取：查不到得 Optional.empty()，不抛异常；查得到则原样返回")
  void getReturnsEmptyWhenAbsent() {
    assertThat(sessionService.get("cli:wang:nobody")).isEmpty();

    Session session = sessionService.getOrCreate("cli", "wang", "default");
    session.appendUserMessage("在的");
    sessionService.save(session);

    assertThat(sessionService.get(session.id())).isPresent();
    assertThat(sessionService.get(session.id()).orElseThrow().messages()).hasSize(1);
  }

  @Test
  @DisplayName("序列化往返：条数、顺序、角色、内容、工具调用与工具调用标识逐条一致")
  void roundTripPreservesEveryMessageField() {
    Session session = sessionService.getOrCreate("cli", "wang", "default");
    session.appendUserMessage("今天天气怎么样？");
    AssistantMessage.ToolCall call =
        new AssistantMessage.ToolCall(
            "call-1", "function", "http_get", "{\"url\":\"https://api.example.com/weather\"}");
    session.appendAssistantMessage("我查一下。", List.of(call));
    session.appendToolResult("call-1", ToolResult.success("晴，26 度"));

    List<SessionMessage> before = session.messages();
    List<SessionMessage> after = MessageJson.fromJson(MessageJson.toJson(before));

    assertThat(after).hasSize(before.size());
    assertThat(after)
        .extracting(SessionMessage::role)
        .containsExactly(
            SessionMessage.Role.USER, SessionMessage.Role.ASSISTANT, SessionMessage.Role.TOOL);
    for (int i = 0; i < before.size(); i++) {
      SessionMessage expected = before.get(i);
      SessionMessage actual = after.get(i);
      assertThat(actual.role()).isEqualTo(expected.role());
      assertThat(actual.content()).isEqualTo(expected.content());
      assertThat(actual.toolCalls()).isEqualTo(expected.toolCalls());
      assertThat(actual.toolCallId()).isEqualTo(expected.toolCallId());
      assertThat(actual.timestamp()).isEqualTo(expected.timestamp());
    }
  }

  @Test
  @DisplayName("含中文 / 换行 / 引号 / emoji 的内容往返后逐字一致")
  void roundTripPreservesTrickyContent() {
    List<String> contents =
        List.of(
            "中文内容：今天天气怎么样？",
            "第一行\n第二行\r\n第三行",
            "他说：\"晴，26 度\"，还有反斜杠 \\ 与制表符 \t",
            "emoji：🌤️ ☀️ 🎉 👨‍👩‍👧‍👦");
    Session session = sessionService.getOrCreate("cli", "wang", "default");
    contents.forEach(session::appendUserMessage);

    List<SessionMessage> after = MessageJson.fromJson(MessageJson.toJson(session.messages()));

    assertThat(after).extracting(SessionMessage::content).containsExactlyElementsOf(contents);
  }

  @Test
  @DisplayName("空历史：往返后得空列表，不报错")
  void emptyHistoryReadsBackAsEmptyList() {
    assertThat(MessageJson.fromJson(MessageJson.toJson(List.of()))).isEmpty();
    assertThat(MessageJson.fromJson(null)).isEmpty();
    assertThat(MessageJson.fromJson("")).isEmpty();
  }

  @Test
  @DisplayName("save 两次：created_at 不被覆盖，status 仍是首次写入的 active")
  void repeatedSaveKeepsCreatedAtAndStatus() {
    Session session = sessionService.getOrCreate("cli", "wang", "default");
    session.appendUserMessage("第一句");
    sessionService.save(session);
    LocalDateTime createdAt = rows.get(session.id()).getCreatedAt();

    session.appendUserMessage("第二句");
    sessionService.save(session);

    SessionEntity row = rows.get(session.id());
    assertThat(row.getCreatedAt()).isEqualTo(createdAt);
    assertThat(row.getStatus()).isEqualTo("active");
    assertThat(row.getArchivedAt()).isNull();
    assertThat(row.getMessagesJson()).contains("第二句");
  }

  @Test
  @DisplayName("更新路径原样保留 status / archived_at：已归档的会话不会被下一次 save 悄悄复活")
  void updatePathPreservesArchivedState() {
    Session session = sessionService.getOrCreate("cli", "wang", "default");
    sessionService.save(session);
    LocalDateTime archivedAt = LocalDateTime.of(2026, 10, 13, 10, 0);
    SessionEntity row = rows.get(session.id());
    row.setStatus("archived");
    row.setArchivedAt(archivedAt);

    session.appendUserMessage("归档后又来了一句");
    sessionService.save(session);

    assertThat(rows.get(session.id()).getStatus()).isEqualTo("archived");
    assertThat(rows.get(session.id()).getArchivedAt()).isEqualTo(archivedAt);
  }

  @Test
  @DisplayName("归档幂等：第二次归档仍返回 true，且 archivedAt 保持首次归档的时刻（FR-021）")
  void archive_isIdempotent() {
    Session session = sessionService.getOrCreate("web", "alice", "daily-weather");
    sessionService.save(session);

    assertThat(sessionService.archive(session.id())).isTrue();
    LocalDateTime firstArchivedAt = rows.get(session.id()).getArchivedAt();
    assertThat(firstArchivedAt).isNotNull();

    assertThat(sessionService.archive(session.id())).isTrue();
    assertThat(rows.get(session.id()).getArchivedAt())
        .as("重复 DELETE 不该把归档时刻改成「现在」")
        .isEqualTo(firstArchivedAt);
  }

  @Test
  @DisplayName("归档从未存在的会话返回 false（与「重复归档」是两回事，调用方据此报 404）")
  void archive_unknownSessionReturnsFalse() {
    assertThat(sessionService.archive("web:alice:nobody")).isFalse();
  }

  @Test
  @DisplayName("countActive 数的是 active 行：归档一条即少一条")
  void countActive_dropsAfterArchive() {
    Session kept = sessionService.getOrCreate("web", "alice", "daily-weather");
    Session archived = sessionService.getOrCreate("web", "bob", "daily-weather");
    sessionService.save(kept);
    sessionService.save(archived);
    assertThat(sessionService.countActive()).isEqualTo(2);

    sessionService.archive(archived.id());

    assertThat(sessionService.countActive()).isEqualTo(1);
    assertThat(rows.get(kept.id()).getStatus()).isEqualTo("active");
  }
}
