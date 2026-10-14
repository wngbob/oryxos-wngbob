package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.NotifyChannelEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 通知渠道全局注册表 {@code notify_channels} 的 Repository（data-model.md §4）。
 *
 * <p>主键类型是 {@code String}——{@code name} 是人指定的注册名，不是自增。
 *
 * <p><b>本节只读</b>：不新增任何写入方法。渠道的增删改归第 26 节（Web 管理台 + {@code /api/v1/notify-channels}）， 本节交付「表 + 存取 +
 * 按名解析」。{@code JpaRepository} 继承来的 {@code save} 不在此列——那是框架给的， 本节无调用方。
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
