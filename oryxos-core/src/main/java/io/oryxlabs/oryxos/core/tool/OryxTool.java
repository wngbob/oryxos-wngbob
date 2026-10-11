package io.oryxlabs.oryxos.core.tool;

/**
 * OryxOS 内部统一的 Tool 抽象（归 {@code oryxos-core}，见技术方案第 10 章模块表与 6.1）。
 *
 * <p>内置 Tool、{@code @Tool} 注解的 Plugin Tool、MCP Tool 都被包装成 {@code OryxTool} 实例 注册到 {@code
 * ToolRegistry}，ReAct 循环不感知具体 Tool 的来源。
 *
 * <p>{@code execute} 的签名不带 Profile：按 Profile 过滤工具子集这类需求从 {@code ProfileContext} 读，不改工具接口（技术方案 6.2）。
 *
 * <p><b>骨架状态</b>：接口签名按技术方案落地，方案未写明参数/返回类型的部分取最直白口径 （JSON 以字符串承载，避免给 core 引入额外依赖）；具体实现随 US-2 / US-4
 * 落地。
 */
public interface OryxTool {

  /** Tool 名称（Profile 的 {@code tools} 字段按名引用）。 */
  String getName();

  /** 供 LLM 判断何时调用的自然语言描述。 */
  String getDescription();

  /** 入参的 JSON Schema 文本（供 Function Calling 使用）。 */
  String getInputSchema();

  /**
   * 执行 Tool。
   *
   * @param inputJson 入参 JSON 文本
   * @return 执行结果，含成功标识、结果内容、错误信息、是否可重试
   */
  ToolResult execute(String inputJson);
}
