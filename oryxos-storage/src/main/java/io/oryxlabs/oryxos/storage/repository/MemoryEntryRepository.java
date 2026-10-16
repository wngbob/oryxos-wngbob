package io.oryxlabs.oryxos.storage.repository;

import io.oryxlabs.oryxos.storage.entity.MemoryEntry;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 长期记忆条目 {@code memory_entries} 的 Repository（data-model.md §2）。
 *
 * <p><b>三个查询对应 SQLite 档的三种取法</b>，共同点是「先按 scope 过滤」——故 {@code scope} 上有索引（{@code idx_memory_scope}）：
 *
 * <ul>
 *   <li>核心区：<b>全量</b>升序，<b>不带任何 LIMIT</b>——契约二「核心记忆永不被截断」在 SQL 层的落点；
 *   <li>归档区：<b>降序取最近 N 条</b>——截断手段从 Markdown 档的「裁字符串」换成这里的「LIMIT」，但只作用在归档区；
 *   <li>归档区检索：<b>全量</b>匹配，<b>不受上面那条 LIMIT 影响</b>——截断只决定「注入上下文的那一份」，否则记忆「存了但查不到」。
 * </ul>
 *
 * <p><b>检索不区分大小写</b>（FR-008 / D10）：用 {@code LOWER(...) LIKE LOWER(...)} 而不是裸 {@code LIKE}。裸 {@code
 * LIKE} 靠 SQLite 驱动的默认行为，跨档口径要由 SQL 自己说清楚——三档行为一致才叫「同一套契约」。
 *
 * <p><b>主键类型是 {@code Long}</b>：记忆条目没有业务键（同一条内容可被重复写入），序号只能是数据库给的。
 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

  /**
   * 核心区全部条目，按写入顺序升序。
   *
   * <p><b>MUST NOT 加分页参数</b>：核心记忆永远全量注入，任何条数上限都会破契约二。
   *
   * @param scope 分区名，核心档传 {@code "CORE"}
   * @return 该分区全部条目，按 id 升序
   */
  List<MemoryEntry> findByScopeOrderByIdAsc(String scope);

  /**
   * 归档区最近的若干条，按写入顺序降序（最新的在前）。
   *
   * <p>截断在 SQL 层完成：调用方传 {@code PageRequest.of(0, 上限条数)} 即可，不必把整表读进内存再截。
   *
   * <p>返回的是降序，调用方要按时间正序渲染时自行 {@code reversed()}——降序是为了让 {@code LIMIT} 取到「最近的」而不是「最早的」。
   *
   * @param scope 分区名，归档档传 {@code "ARCHIVAL"}
   * @param pageable 分页参数，核心阶段只传「取前 N 条」
   * @return 该分区最近 N 条，按 id 降序
   */
  List<MemoryEntry> findByScopeOrderByIdDesc(String scope, Pageable pageable);

  /**
   * 归档区关键词检索，不区分大小写，覆盖归档区<b>全量</b>。
   *
   * <p><b>MUST NOT 分页</b>：检索范围与 {@code load()} 的截断无关（FR-009）。归档区被裁掉的内容仍要能被查到，否则 Agent
   * 会以为「这件事我没记过」而重复询问用户。
   *
   * <p>{@code scope} 写成 JPQL 字面量而不是参数：分区口径由本接口定死（检索只搜归档区，FR-007），留成参数等于给调用方一个 把核心区也搜进去的口子。
   *
   * @param pattern 已经拼好通配符的匹配串，形如 {@code "%Java%"}
   * @return 命中的条目，按 id 升序
   */
  @Query(
      "SELECT m FROM MemoryEntry m WHERE m.scope = 'ARCHIVAL'"
          + " AND LOWER(m.content) LIKE LOWER(:pattern) ORDER BY m.id ASC")
  List<MemoryEntry> searchArchival(@Param("pattern") String pattern);
}
