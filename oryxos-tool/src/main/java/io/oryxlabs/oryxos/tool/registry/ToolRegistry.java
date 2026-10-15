package io.oryxlabs.oryxos.tool.registry;

import io.oryxlabs.oryxos.core.profile.Profile;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 三源汇聚的工具注册表，实现 core 的 {@link ToolCatalog} 接缝（contracts/tool-abstraction.md §2）。
 *
 * <p>内置工具、业务方的 {@code @Tool} Bean、外部 MCP server 拉来的工具，全部在这里收敛成统一的 {@link OryxTool}——ReAct
 * 循环因此完全不感知工具从哪来（章程原则 V）。{@code AGENT.md} MUST NOT 注册进来（章程原则 IV）。
 *
 * <p><b>它同时也是 FR-022「加一个工具的成本恒定在写一个类」的兑现处</b>：新工具只需在装配处加一次注册，本类、过滤逻辑、审计路径一行都不用改。
 *
 * <p><b>不持跨调用的可变业务状态</b>（章程原则 VIII）：注册在启动期完成，之后只读；注册表本身可从代码与配置重建，不落库。
 */
public class ToolRegistry implements ToolCatalog {

  private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

  /** 注册顺序即遍历顺序：{@code tool list} 的输出与重名判定都依赖它稳定。 */
  private final Map<String, RegisteredTool> byName = new LinkedHashMap<>();

  /**
   * 登记一个工具。
   *
   * <p><b>重名保留先注册者 + WARN</b>（FR-023，安全侧优先）：内置工具的名字是 OryxOS 自己定的、稳定；MCP server 的名字来自外部、
   * 可能撞车。撞车时保住先注册的那个更可预期。装配顺序因此确定——内置与注解 Bean 先、MCP 后。
   *
   * @param tool 工具；为 {@code null} 抛 {@link NullPointerException}
   * @param source 来源标签：{@code builtin} / {@code annotation} / {@code mcp:<server名>}
   * @throws NullPointerException {@code tool} 为 {@code null}
   * @throws IllegalArgumentException {@code tool.getName()} 为空白（契约三件套之一，注册时就该拦住）
   */
  public void register(OryxTool tool, String source) {
    if (tool == null) {
      throw new NullPointerException("工具不能为 null");
    }
    String name = tool.getName();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("工具名不能为空——名字是模型引用它的唯一凭据");
    }
    RegisteredTool existing = byName.get(name);
    if (existing != null) {
      log.atWarn()
          .addKeyValue("tool", name)
          .addKeyValue("keptSource", existing.source())
          .addKeyValue("droppedSource", source)
          .log("工具名已注册，保留先注册者、丢弃后到的这一个");
      return;
    }
    byName.put(name, new RegisteredTool(tool, source));
  }

  /**
   * 扫描 Bean 上所有带 {@link Tool @Tool} 的方法，逐个包装成 {@link OryxTool} 并登记。
   *
   * <p><b>扫到空不报错</b>：业务方可能还没写工具，这是正常状态。
   *
   * <p><b>方法按名字排序后注册</b>：反射给出的方法顺序在 JVM 实现之间不作保证，不排序会让 {@code tool list} 的输出与重名判定变成 「取决于运行时」的偶然事实。
   *
   * @param bean 业务方的 Bean；为 {@code null} 抛 {@link NullPointerException}
   * @param source 来源标签
   */
  public void registerAnnotated(Object bean, String source) {
    if (bean == null) {
      throw new NullPointerException("Bean 不能为 null");
    }
    for (Method method : annotatedMethods(bean)) {
      register(new AnnotatedToolAdapter(bean, method), source);
    }
  }

  /**
   * 注册表里是否有这个名字。
   *
   * <p>MCP 失联隔离的回归测试就是用它断言「坏 server 的工具没注册进来」（SC-004）。
   *
   * @param name 工具名
   * @return 是否已注册
   */
  public boolean contains(String name) {
    return byName.containsKey(name);
  }

  /**
   * 全量条目（工具 + 来源），顺序 = 注册顺序。
   *
   * <p>消费方：{@code oryxos tool list}（本节）、第 26 节的 {@code /api/v1/tools} REST 端点。来源信息由本方法提供， <b>因此不扩
   * {@link ToolCatalog}</b>——来源是注册关系的属性，不是工具的属性。
   *
   * @return 不可变快照
   */
  public List<RegisteredTool> registered() {
    return List.copyOf(byName.values());
  }

  @Override
  public Optional<OryxTool> find(String name) {
    RegisteredTool entry = name == null ? null : byName.get(name);
    return entry == null ? Optional.empty() : Optional.of(entry.tool());
  }

  /**
   * 按 Profile 声明的清单过滤出可用子集。
   *
   * <p><b>结果恰好等于能解析到的声明名集合</b>（FR-005）：多一个（没过滤干净）和少一个（过滤过头）都是错。三条硬规则：
   *
   * <ol>
   *   <li>顺序 = {@code profile.tools()} 的<b>声明顺序</b>（不按注册顺序），便于对账；
   *   <li>声明了但注册表里没有的名字 → WARN + 跳过，不抛异常、不塞空占位；
   *   <li>{@code profile.tools()} 为空 → 返回空列表（「这个 Agent 不用任何工具」是合法配置，不是错误）。
   * </ol>
   *
   * @param profile 当前 Agent 的运行时配置
   * @return 可用工具
   */
  @Override
  public List<OryxTool> availableFor(Profile profile) {
    if (profile == null) {
      return List.of();
    }
    List<OryxTool> available = new ArrayList<>();
    for (String name : profile.tools()) {
      RegisteredTool entry = byName.get(name);
      if (entry == null) {
        log.atWarn()
            .addKeyValue("agent", profile.name())
            .addKeyValue("tool", name)
            .log("Agent 声明的工具不在注册表里，已跳过");
        continue;
      }
      available.add(entry.tool());
    }
    return List.copyOf(available);
  }

  /** 收集带 {@code @Tool} 的方法；用 {@code TreeSet} 按签名排序，让注册顺序可复现。 */
  private static List<Method> annotatedMethods(Object bean) {
    TreeSet<Method> methods =
        new TreeSet<>(Comparator.comparing(Method::getName).thenComparing(Method::toGenericString));
    for (Method method : bean.getClass().getMethods()) {
      if (method.isAnnotationPresent(Tool.class)) {
        methods.add(method);
      }
    }
    return List.copyOf(methods);
  }

  /**
   * 注册表里的一条记录。
   *
   * <p><b>为什么嵌在这里而不是顶层类型</b>：把本节新增的 public 顶层类型压到 0 个，且语义上它确实是「注册表里的一条记录」， 不是独立概念。
   *
   * @param tool 工具
   * @param source 来源标签：{@code builtin} / {@code annotation} / {@code mcp:<server名>}
   */
  public record RegisteredTool(OryxTool tool, String source) {}
}
