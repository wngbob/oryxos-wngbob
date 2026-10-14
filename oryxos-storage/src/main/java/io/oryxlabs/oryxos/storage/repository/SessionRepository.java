package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话表 {@code sessions} 的 Repository（技术方案第 10 章模块表）。
 *
 * <p>主键类型是 {@code String}——{@code session_id} 由 {@code channel:user:profile} 三段拼接生成，不是自增。
 *
 * <p>本节不新增查询方法：{@code SessionService} 的三个操作（取或建 / 按标识取 / 保存）用 {@code findById} / {@code existsById}
 * / {@code save} 即可表达。
 */
public interface SessionRepository extends JpaRepository<SessionEntity, String> {}
