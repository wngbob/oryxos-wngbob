package io.oryxlabs.oryxos.core.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 已装载 Profile 的内存索引，按 Agent 名查找（FR-002）。
 *
 * <p>启动时一次性装载、之后只读：核心阶段不支持运行时增删 Agent（那是第 29/30 节的事），所以这里没有任何写方法—— 一个不可变对象比一个"暂时没人调"的可变对象更诚实。
 *
 * <p>同名 Agent 后者覆盖前者：Agent 名即目录名，文件系统里不可能有两个同名目录，真撞上说明调用方传了脏数据， 此处不抛异常，由上游 {@link ProfileLoader}
 * 的日志负责暴露问题。
 */
public class ProfileRegistry {

  private final Map<String, Profile> byAgentName;

  /**
   * @param profiles 已装载的 Profile；同名时以最后一条为准
   */
  public ProfileRegistry(List<Profile> profiles) {
    Map<String, Profile> index = new LinkedHashMap<>();
    for (Profile profile : profiles) {
      index.put(profile.name(), profile);
    }
    // 保持装载顺序（按目录名升序），便于 CLI 列 Agent 时输出稳定
    this.byAgentName = Collections.unmodifiableMap(index);
  }

  /**
   * 按 Agent 名查找。
   *
   * @param agentName Agent 名
   * @return 命中则返回 Profile，否则空——调用方决定"查不到"是报错还是跳过
   */
  public Optional<Profile> find(String agentName) {
    return Optional.ofNullable(byAgentName.get(agentName));
  }

  /**
   * @return 全部已装载的 Profile，按装载顺序
   */
  public List<Profile> all() {
    return List.copyOf(byAgentName.values());
  }

  /**
   * @return 全部 Agent 名
   */
  public Set<String> names() {
    return byAgentName.keySet();
  }
}
