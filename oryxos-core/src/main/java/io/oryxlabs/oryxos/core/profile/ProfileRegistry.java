package io.oryxlabs.oryxos.core.profile;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已装载 Profile 的内存索引，按 Agent 名查找（FR-002）。
 *
 * <p><b>第 29 节起可变</b>：{@link #register(Profile)} / {@link #remove(String)} / {@link
 * #exists(String)} 供运行时增删（契约 runtime-registration §2）。底层是 {@link ConcurrentHashMap}——注册发生在启动线程、
 * 查询发生在 HTTP / 调度线程，两边并发读写是常态。
 *
 * <p><b>不持久化</b>：索引只是 {@code .oryxos/agents/} 的运行时投影，真相源永远是磁盘上的 Agent 目录。每次启动由装配处重新扫描重建；
 * 因此重启后运行时注册的临时 Agent 自然消失，这是预期行为、不是丢数据。
 *
 * <p><b>快照不可变</b>：{@link #all()} / {@link #names()} 返回按 Agent 名排序的<b>不可变副本</b>——调用方拿到的清单不会被后续
 * {@code register} 悄悄改动，遍历时也不必担心并发修改。刻意不用 {@code Set.copyOf}：它对元素顺序不作任何保证， 排序必须来自 {@link TreeSet}。
 *
 * <p>同名 Agent 后者覆盖前者：Agent 名即目录名，文件系统里不可能有两个同名目录，真撞上说明调用方传了脏数据， 此处不抛异常，由上游 {@link ProfileLoader}
 * 的日志负责暴露问题。
 */
public class ProfileRegistry {

  private final Map<String, Profile> byAgentName = new ConcurrentHashMap<>();

  /**
   * @param profiles 启动扫描得到的 Profile；同名时以最后一条为准
   */
  public ProfileRegistry(List<Profile> profiles) {
    for (Profile profile : profiles) {
      register(profile);
    }
  }

  /**
   * 运行时注册（或替换）一个 Profile：返回后立即可被 {@link #find(String)} 看到。
   *
   * @param profile 已派生好的 Profile；同名条目被覆盖
   */
  public void register(Profile profile) {
    Objects.requireNonNull(profile, "profile");
    Objects.requireNonNull(profile.name(), "profile.name");
    byAgentName.put(profile.name(), profile);
  }

  /**
   * 运行时移除一个 Profile。
   *
   * <p>只动索引、不碰磁盘上的 Agent 目录——「卸载」与「删除业务方的文件」是两回事，后者不该由本类替调用方决定。
   *
   * @param agentName Agent 名
   * @return 确实移除了一条返回 {@code true}；本来就没有返回 {@code false}
   */
  public boolean remove(String agentName) {
    return byAgentName.remove(Objects.requireNonNull(agentName, "agentName")) != null;
  }

  /**
   * @param agentName Agent 名
   * @return 索引里是否有这个 Agent
   */
  public boolean exists(String agentName) {
    return byAgentName.containsKey(Objects.requireNonNull(agentName, "agentName"));
  }

  /**
   * 按 Agent 名查找。
   *
   * @param agentName Agent 名
   * @return 命中则返回 Profile，否则空——调用方决定"查不到"是报错还是跳过
   */
  public Optional<Profile> find(String agentName) {
    return Optional.ofNullable(byAgentName.get(Objects.requireNonNull(agentName, "agentName")));
  }

  /**
   * @return 全部已装载的 Profile，按 Agent 名排序的不可变快照
   */
  public List<Profile> all() {
    return List.copyOf(new TreeMap<>(byAgentName).values());
  }

  /**
   * @return 全部 Agent 名，按名排序的不可变快照
   */
  public Set<String> names() {
    return Collections.unmodifiableSet(new TreeSet<>(byAgentName.keySet()));
  }
}
