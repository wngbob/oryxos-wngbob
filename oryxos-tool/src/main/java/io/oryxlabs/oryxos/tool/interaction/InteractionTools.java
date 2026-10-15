package io.oryxlabs.oryxos.tool.interaction;

import io.oryxlabs.oryxos.core.tool.ToolResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置交互工具：{@code ask_user}（contracts/builtin-tools.md §2.9；FR-017）。
 *
 * <p><b>没有沙箱动作</b>：这个工具不碰文件、不跑进程、不发网络请求——{@code Sandbox} 的四种 {@code ActionType} 一种都不沾。硬加一条 {@code
 * enforce} 只会让「每个涉外工具的首行都是 enforce」这条不变量失去区分度（data-model.md §1.7 的动作映射表里 {@code ask_user} 本就不在列）。
 *
 * <p><b>拿不到回答时明确报错，绝不静默阻塞</b>：定时任务与无状态调用根本没有另一端的人在等。静默阻塞会让钟推任务挂死到超时， 且日志里看不出原因；一条明确的失败结果则同时进对话、进
 * {@code tool_invocations} 审计表。
 */
public class InteractionTools {

  private final UserInteraction interaction;

  public InteractionTools(UserInteraction interaction) {
    this.interaction = interaction;
  }

  /**
   * 向用户提问并等待回答。
   *
   * @param question 要问用户的问题
   * @return 拿到回答时为成功结果（<b>空串也是合法回答</b>）；当前渠道问不到时为失败结果（不可重试——换一档渠道才有意义， 重试同一个问题不会变好）
   */
  @Tool(name = "ask_user", description = "向用户提问并等待回答；当前渠道不支持交互时明确报错，不会卡住")
  public ToolResult askUser(@ToolParam(description = "要问用户的问题") String question) {
    return interaction
        .ask(question)
        .map(ToolResult::success)
        .orElseGet(() -> ToolResult.failure("当前渠道不支持与用户交互"));
  }
}
