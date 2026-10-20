package io.oryxlabs.oryxos.core.session;

import io.oryxlabs.oryxos.storage.entity.SessionEntity;
import io.oryxlabs.oryxos.storage.repository.SessionRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * {@link SessionService} 的 JPA 实现（contracts/session-persistence.md §1）。
 *
 * <p><b>为什么放在 core 而不是 storage</b>：{@code oryxos-core → oryxos-storage} 是已报批的既定边（storage 是
 * 共享基础设施模块），与第 17 节 {@code ToolExecutor} 直接用 {@code ToolInvocationRepository} 是同一模式； 放 storage
 * 会成环。序列化口径（{@link MessageJson}）也在 core。
 *
 * <p><b>不加 {@code @Component}</b>：由装配点以 {@code @Bean} 声明，「谁装配、装配成什么」只在一处可见（第 26 节 需要时复用同一个
 * {@code @Bean} 方法，不得复制一份）。
 *
 * <p><b>本节不做</b>：归档（{@code status} / {@code archived_at} 的第二态归第 26 节）、按条拆表。 （修订注，2026-10-19，第 26
 * 节：归档已由 {@link #archive(String)} 补齐——这是第 18 节预先留好的位置，{@link #save(Session)} 的更新列集合当时就已收窄到 {@code
 * messages_json} + {@code last_active_at}，故归档态不会被下一次写入复活。）
 *
 * <p><b>抑制 P3C 的 {@code ServiceOrDaoClassShouldEndWithImplRule}</b>：该规则要求 Service 接口的实现类以 {@code
 * Impl} 结尾。本类的命名口径是<b>技术前缀</b>而非 {@code Impl} 后缀——与第 17 节 {@code JpaLlmCallRecorder} 一致，类名本身已经
 * 说清「接口是 {@link SessionService}、实现是 JPA 版」，再叠一层 {@code Impl} 只是重复。规则想守的「接口与实现分离」本类
 * 已经满足，故按<b>单类</b>抑制而不是在 {@code p3c-ruleset.xml} 里放水——后者会让这条规则对全仓永久失效。
 */
@SuppressWarnings("PMD.ServiceOrDaoClassShouldEndWithImplRule")
public class JpaSessionService implements SessionService {

  private final SessionRepository repository;

  /**
   * @param repository 会话表的存取出口
   */
  public JpaSessionService(SessionRepository repository) {
    this.repository = repository;
  }

  @Override
  public Session getOrCreate(String channel, String userId, String profileName) {
    String sessionId = SessionId.of(channel, userId, profileName);
    Optional<SessionEntity> existing = repository.findById(sessionId);
    return existing.isPresent()
        ? toSession(existing.get())
        : Session.create(sessionId, channel, userId, profileName);
  }

  @Override
  public Optional<Session> get(String sessionId) {
    return repository.findById(sessionId).map(JpaSessionService::toSession);
  }

  @Override
  public void save(Session session) {
    // 先序列化再碰库：序列化失败必须一行都不写，不能留半条（不变量 ③）
    String messagesJson = MessageJson.toJson(session.messages());
    String sessionId = session.id();
    SessionEntity entity = repository.findById(sessionId).orElse(null);
    if (entity == null) {
      repository.save(newRow(session, messagesJson));
      return;
    }
    // 更新路径只动这两列：created_at 与 status / archived_at 原样保留（不变量 ① / ②），
    // 否则第 26 节一归档就被下一次 save 悄悄复活
    entity.setMessagesJson(messagesJson);
    entity.setLastActiveAt(toLocalDateTime(session.lastActiveAt()));
    repository.save(entity);
  }

  @Override
  public boolean archive(String sessionId) {
    SessionEntity entity = repository.findById(sessionId).orElse(null);
    if (entity == null) {
      return false;
    }
    // 已归档就一行都不写：重复 DELETE 必须返回首次那个 archivedAt，而不是把时刻改成「现在」
    if (!Session.STATUS_ARCHIVED.equals(entity.getStatus())) {
      entity.setStatus(Session.STATUS_ARCHIVED);
      entity.setArchivedAt(LocalDateTime.now());
      repository.save(entity);
    }
    return true;
  }

  @Override
  public long countActive() {
    return repository.countByStatus(Session.STATUS_ACTIVE);
  }

  private static SessionEntity newRow(Session session, String messagesJson) {
    SessionEntity entity = new SessionEntity();
    entity.setSessionId(session.id());
    entity.setProfileName(session.profileName());
    entity.setChannel(session.channel());
    entity.setUserId(session.userId());
    entity.setMessagesJson(messagesJson);
    entity.setStatus(Session.STATUS_ACTIVE);
    entity.setCreatedAt(toLocalDateTime(session.createdAt()));
    entity.setLastActiveAt(toLocalDateTime(session.lastActiveAt()));
    entity.setArchivedAt(null);
    return entity;
  }

  private static Session toSession(SessionEntity entity) {
    return Session.restore(
        entity.getSessionId(),
        entity.getChannel(),
        entity.getUserId(),
        entity.getProfileName(),
        MessageJson.fromJson(entity.getMessagesJson()),
        toInstant(entity.getCreatedAt()),
        toInstant(entity.getLastActiveAt()),
        entity.getStatus(),
        toInstant(entity.getArchivedAt()));
  }

  /**
   * {@code Session} 用 {@code Instant}（绝对时刻），实体列用 {@code LocalDateTime}（与 {@code LlmCall} 同口径）。
   * 转换只在此一处，避免两个方向各写一遍导致时区口径漂移。
   */
  private static LocalDateTime toLocalDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
  }

  private static Instant toInstant(LocalDateTime localDateTime) {
    // archived_at 可空：未归档的行这一列就是 NULL，不是「没有时间戳」这种异常
    return localDateTime == null ? null : localDateTime.atZone(ZoneId.systemDefault()).toInstant();
  }
}
