package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 通知渠道全局注册表 {@code notify_channels} 的 Repository（data-model.md §4）。
 *
 * <p>主键类型是 {@code String}——{@code name} 是人指定的注册名，不是自增。
 *
 * <p><b>本节只读</b>：不新增任何写入方法，本节交付「表 + 存取 + 按名解析」。{@code JpaRepository} 继承来的 {@code save}
 * 不在此列——那是框架给的，本节无调用方。
 *
 * <p><b>2026-10-15 修订（补强②）</b>：原文写「渠道的增删改归第 26 节（Web 管理台 + {@code
 * /api/v1/notify-channels}）」——那个挂账是错的，第 26 节的管理平台明确「这一版只读」。写入面已由 {@code oryxos notify-channel
 * add|remove} 交付，但那是**轻命令**：不启 Spring，因此拿不到本 Repository，直接走原生 JDBC 写 {@code notify_channels}
 * 表（{@code WorkspaceDb.execute}）。<b>所以本接口仍然只读，且这不是临时状态</b>—— 第 26 节做 REST 时才会给这里补写入方法（那时有容器可用）。
 */
public interface NotifyChannelRepository extends JpaRepository<NotifyChannelEntity, String> {

  /**
   * 默认渠道：按注册名升序的第一个。
   *
   * <p>用派生查询而不是 {@code findAll()} + 内存排序：SQLite 不保证无 {@code ORDER BY} 的行序，且 {@code findAll()}
   * 会把整表读进内存。默认渠道的确定性口径必须落在 SQL 层（下推成 {@code ORDER BY name ASC LIMIT 1}）。
   *
   * <p>表为空时返回 {@link Optional#empty()}——调用方据此报「未配置任何通知渠道」，MUST NOT 退化成静默无操作。
   *
   * @return 注册名最小的那个渠道；表为空时为空 {@link Optional}
   */
  Optional<NotifyChannelEntity> findFirstByOrderByNameAsc();
}
