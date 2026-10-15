package io.oryxlabs.oryxos.tool.interaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 交互接缝的终端实现：把问题打到标准输出，从标准输入读一行（FR-017）。
 *
 * <p><b>与 {@code chat} 循环共用同一个 stdin，但不会争抢</b>：ReAct 循环是同步阻塞执行的（章程原则 VIII），{@code ask_user}
 * 那一步与对话读行不会真的并发——Agent 问、用户在同一个终端答，一问一答顺序发生。
 *
 * <p><b>EOF 与空行是两件事</b>：{@code readLine()} 返回 {@code null} 表示输入流结束（管道被关、{@code
 * Ctrl-D}），此刻<b>问不到人</b> 了，返回 {@link
 * Optional#empty()}；返回空串表示用户按了回车，那是<b>一个合法的空回答</b>，原样带回。把两者混为一谈，会让 「用户什么都没说」和「没人在听」变成同一个结果。
 *
 * <p><b>读失败不抛异常</b>：{@code IOException} 同样归为「问不到」返回 {@link Optional#empty()}——调用方 （{@code
 * InteractionTools}）会把它转成一条明确的「当前渠道不支持与用户交互」失败结果。这里抛异常只会让失败路径多绕一层。
 */
public class ConsoleUserInteraction implements UserInteraction {

  private final BufferedReader in;

  private final PrintStream out;

  /** 生产构造：读写进程的标准输入输出。 */
  public ConsoleUserInteraction() {
    this(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
  }

  /**
   * 包级私有：单测注入内存里的输入流，免得去动进程级的 {@code System.in}。
   *
   * <p>与第 19 节 {@code WebhookNotifyAdapter} 的包级私有构造同款惯例：测试够得着、生产够不着、不新增任何 public 概念。
   *
   * @param in 读取用户回答的字符流
   * @param out 打印问题的字符流
   */
  ConsoleUserInteraction(BufferedReader in, PrintStream out) {
    this.in = in;
    this.out = out;
  }

  @Override
  public Optional<String> ask(String question) {
    out.println(question);
    out.flush();
    try {
      String line = in.readLine();
      return line == null ? Optional.empty() : Optional.of(line);
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
