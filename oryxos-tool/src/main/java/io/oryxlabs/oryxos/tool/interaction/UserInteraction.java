package io.oryxlabs.oryxos.tool.interaction;

import java.util.Optional;

/**
 * 「向用户提问」的前向接缝（data-model.md §1.8；FR-017）。
 *
 * <p><b>为什么是一个接缝而不是直接读 {@code System.in}</b>：能不能问，取决于<b>当前渠道有没有另一端的人在等</b>。CLI 场景可以读终端， 但定时任务（第 25
 * 节）与 Web 无状态调用（第 26 节）根本没有人在另一端——直接读 {@code System.in} 会让钟推任务挂死到超时，且日志里
 * 看不出原因。所以工具只管把问题递出去、把回答带回来，怎么问、能不能问交给装配时注入的实现决定。这与第 19 节 {@code NotifyChannelAdapter}、本节 {@code
 * Sandbox} 是同一个「接口表达意图、实现按渠道挂」的套路。
 *
 * <p><b>为什么返回 {@code Optional} 而不是返回空串或抛异常</b>：接缝必须能表达「问不到」。返回空串会被下游当成「用户回答了空内容」，
 * 语义完全错；抛异常则把「渠道不支持」这个正常状态伪装成故障。<b>空串是合法回答</b>（用户按了回车），「问不到」是 {@link
 * Optional#empty()}——两者的区别正是这个返回类型存在的理由。
 *
 * <p><b>MUST NOT 阻塞</b>：实现方要么立刻拿到回答，要么立刻返回 {@link Optional#empty()}。等不到答案时挂住不返回，就是把
 * 「渠道不支持」变成「进程卡死」。
 */
public interface UserInteraction {

  /**
   * 把一个提问递给当前渠道，带回用户的回答。
   *
   * @param question 要问用户的问题
   * @return 用户的回答（<b>空串是合法回答</b>）；当前渠道问不到时返回 {@link Optional#empty()}
   */
  Optional<String> ask(String question);
}
