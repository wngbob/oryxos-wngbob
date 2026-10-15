package io.oryxlabs.oryxos.tool.interaction;

import java.util.Optional;

/**
 * 交互接缝的无人值守档：一律「问不到」（FR-017）。
 *
 * <p><b>它存在的意义是把「这里没有人」变成一个显式的装配选择</b>。定时任务（第 25 节）与 Web 无状态调用（第 26 节）没有另一端的人在等，
 * 装配处显式挂上本档——比起「忘了装配导致注入失败」或「碰巧挂了终端实现导致钟推任务挂死到超时」，显式挂一档的结果是 {@code ask_user}
 * 立刻返回一条明确的「当前渠道不支持与用户交互」。
 *
 * <p><b>报错文案不在这里</b>：本档只负责说「问不到」（返回 {@link Optional#empty()}），把它翻译成失败结果是 {@link InteractionTools}
 * 的事——接缝只管传递「有没有回答」，措辞归调用方，这样换一档实现也不用改文案。
 */
public class UnsupportedUserInteraction implements UserInteraction {

  @Override
  public Optional<String> ask(String question) {
    return Optional.empty();
  }
}
