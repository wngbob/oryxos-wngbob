package io.oryxlabs.oryxos.tool.registry;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.ai.util.json.JsonParser;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

/**
 * 把业务方一个带 {@link Tool @Tool} 注解的方法包装成 {@link OryxTool}（contracts/tool-abstraction.md §3）。
 *
 * <p>这是三档接入方式里门槛最高的那一档（方式三「重代码」）的落地：业务方只需写一个普通 Java 方法并加注解，其余（安全校验位、审计落库、 按 Agent 过滤）由底座自动接上。
 *
 * <p><b>MUST NOT 用 Spring AI 的 {@code MethodToolCallback}</b>（三条硬理由）：
 *
 * <ol>
 *   <li>它把返回值字符串化，{@link ToolResult#retryable()} 会丢——而 {@code retryable} 是给循环看的（FR-003）；
 *   <li>第 19 节已交付的 {@code NotifyTools.notify} 返回 {@code ToolResult}，迁就它就得改前序节的公共接口（软门禁 ④）；
 *   <li>它把异常包成错误文本返回，失败落不进 {@code tool_invocations}（违反 FR-021 / 章程原则 VI）。
 * </ol>
 *
 * <p><b>异常一律上抛</b>：{@code method.invoke} 抛出的 {@code InvocationTargetException} 会被剥掉外壳再抛，让 {@code
 * ToolExecutor} 的既有 {@code catch (RuntimeException)} 拿到<b>业务异常本身</b>（而不是一层反射包装），
 * 审计表里的失败原因才是业务方写的那个消息。
 */
public class AnnotatedToolAdapter implements OryxTool {

  private final Object bean;
  private final Method method;
  private final String name;
  private final String description;
  private final String inputSchema;

  /**
   * <b>关于两处 {@code @SuppressFBWarnings}</b>：{@code method} 与 {@code bean} 都是注入的协作对象（业务方的 Bean
   * 与其方法）， 按引用持有是这一档接入方式的全部意义所在，SpotBugs 的 EI_EXPOSE_REP2 在此为误报。{@code setAccessible(true)} 被报
   * DP_DO_INSIDE_DO_PRIVILEGED（SpotBugs 建议包一层 {@code AccessController.doPrivileged}）——但那是为
   * SecurityManager 设计的，而 JDK 21 已不可用 SecurityManager（章程原则 VII），包一层只剩形式。两处都就地豁免并留下理由。
   *
   * @param bean 方法所属的 Bean 实例
   * @param method 带 {@code @Tool} 注解的方法
   */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2", "DP_DO_INSIDE_DO_PRIVILEGED"},
      justification =
          "bean/method 是注入的协作对象，按引用持有即本适配器的职责；setAccessible 只用于调业务方自己的方法，JDK 21 无 SecurityManager 可言（章程原则 VII）")
  public AnnotatedToolAdapter(Object bean, Method method) {
    this.bean = bean;
    this.method = method;
    Tool annotation = method.getAnnotation(Tool.class);
    // @Tool.name() 为空时退回 Spring AI 的命名规则（方法名）；有 name 就用 name——业务方显式指定的名字优先
    // 只判空白不判 null：@Tool.name() 的 AnnotationDefault 是 ""，注解元素不可能取到 null（javap 核实）
    String declaredName = annotation == null ? "" : annotation.name();
    this.name = declaredName.isBlank() ? ToolUtils.getToolName(method) : declaredName;
    this.description = annotation == null ? null : annotation.description();
    this.inputSchema = JsonSchemaGenerator.generateForMethodInput(method);
    this.method.setAccessible(true);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getInputSchema() {
    return inputSchema;
  }

  /**
   * 反射调用目标方法，把 JSON 入参摊成实参。
   *
   * <p><b>参数名从哪来</b>：{@code Parameter.getName()}。它只在编译期打开了 {@code -parameters} 时才返回真实名字，否则退化成
   * {@code arg0} / {@code arg1}——那时 schema 与执行两边都拿不到真名，模型无法构造调用。父 pom 的 {@code
   * maven-compiler-plugin} 已打开该开关，并由 {@code AnnotatedToolAdapterTest} 钉死（D2a）。
   *
   * <p><b>缺参不静默补 {@code null}</b>：模型漏传一个必填参数时，静默补 null 会让它拿到一个「参数为 null 的诡异结果」而不是 「参数错了」的明确反馈。判定口径与
   * schema 生成器保持一致——没标 {@code @ToolParam} 的参数按<b>必填</b>处理（{@code ToolParam.required()} 默认就是 {@code
   * true}），否则会出现「schema 说要填、执行时却放行」的两套口径。
   *
   * @param inputJson 模型给的入参 JSON；空串等同 {@code {}}
   * @return 方法返回值包装成的结果
   */
  @Override
  public ToolResult execute(String inputJson) {
    Map<String, Object> arguments = parse(inputJson);
    Parameter[] parameters = method.getParameters();
    Object[] args = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      String parameterName = parameter.getName();
      if (!arguments.containsKey(parameterName)) {
        if (isRequired(parameter)) {
          return ToolResult.failure("缺少必填参数: " + parameterName);
        }
        args[i] = null;
        continue;
      }
      Object raw = arguments.get(parameterName);
      args[i] =
          raw == null ? null : JsonParser.getObjectMapper().convertValue(raw, parameter.getType());
    }
    return toResult(invoke(args));
  }

  /** 入参是模型给的文本，可能为空串（无参工具）——空串按空对象处理，不当作错误。 */
  private static Map<String, Object> parse(String inputJson) {
    if (inputJson == null || inputJson.isBlank()) {
      return Map.of();
    }
    // 走 requireNonNullElse 而不是三元判空：JSON 文本是字面量 "null" 时 fromJson 确实返回 null，但 SpotBugs 认定该返回值
    // 非空、把显式判空报成 RCN_REDUNDANT_NULLCHECK。语义一样，写法换成它不认得的那个
    return Objects.requireNonNullElse(JsonParser.fromJson(inputJson, Map.class), Map.of());
  }

  /**
   * 参数是否必填。
   *
   * <p>口径与 {@code JsonSchemaGenerator} 一致：无 {@code @ToolParam} 注解即必填。{@code @ToolParam} 只有 {@code
   * required()} 与 {@code description()} 两个元素，<b>没有 {@code name()}</b>——参数名只能来自 {@code -parameters}。
   */
  private static boolean isRequired(Parameter parameter) {
    ToolParam annotation = parameter.getAnnotation(ToolParam.class);
    return annotation == null || annotation.required();
  }

  /** 反射调用；把反射包装层剥掉，让业务异常本身浮上来（ToolExecutor 记的是业务消息，不是 InvocationTargetException）。 */
  private Object invoke(Object[] args) {
    try {
      return method.invoke(bean, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      // 受检异常：@Tool 方法签名上通常不会声明，但反射调用能遇到（如 Method.invoke 的 IllegalAccessException）
      throw new IllegalStateException(cause);
    } catch (IllegalAccessException e) {
      // 构造时已 setAccessible(true)，走到这里说明模块化访问被拒——是编程错误，不静默
      throw new IllegalStateException("无法访问工具方法: " + method.getName(), e);
    }
  }

  /**
   * 返回值包装。
   *
   * <p>返回 {@link ToolResult} 时<b>原样返回</b>——这是 {@code retryable} 不被丢掉的唯一途径；其它类型按字符串包成成功结果。
   */
  private static ToolResult toResult(Object returned) {
    if (returned instanceof ToolResult result) {
      return result;
    }
    return ToolResult.success(String.valueOf(returned));
  }
}
