package io.oryxlabs.oryxos.channel.cli;

import io.oryxlabs.oryxos.core.engine.AgentService;
import io.oryxlabs.oryxos.core.session.Session;
import io.oryxlabs.oryxos.core.session.SessionService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * {@code channel = cli} 的入站适配：读一行 → 交给引擎 → 打印结果，循环往复（FR-004 / FR-005）。
 *
 * <p><b>本类不碰模型调用与工具调度</b>：它只做「读输入、交给 {@link AgentService#process}、打印结果」三步， 全部 Agent 逻辑归引擎（章程原则
 * IX）。这也正是三条触发源（CLI / Web / 钟推）能复用同一条链路的原因。
 *
 * <p><b>MUST NOT 自己拼 {@code session_id}</b>：本类只交出 {@code channel} / {@code userId} / {@code
 * profileName} 三元组，标识由 {@link SessionService} 内部唯一一处拼接（FR-006 / H4 不变量 ④）。两处各拼一遍、格式差一个分隔符，
 * 同一个人就会出现两条互不相认的历史。
 *
 * <p><b>渠道与用户是固定字面量</b>：{@code cli} / {@code local}（仲裁 13）。同一个 Agent 从 CLI 与从 HTTP 接入会是两条历史，
 * 因为它们的三元组不同——这是设计意图，不是缺陷。
 */
public final class CliChannel {

  /** 接入渠道标识；会话标识的第一段。 */
  private static final String CHANNEL = "cli";

  /** 本地用户标识；会话标识的第二段。本节不引入任何用户体系（企业级认证属扩展阶段）。 */
  private static final String LOCAL_USER = "local";

  /** 退出指令。 */
  private static final String QUIT = "/quit";

  /** 交互提示符。 */
  private static final String PROMPT = "> ";

  private final AgentService agentService;
  private final SessionService sessionService;

  /**
   * @param agentService 三种触发源的唯一入口
   * @param sessionService 取会话；本类只提供三元组
   */
  public CliChannel(AgentService agentService, SessionService sessionService) {
    this.agentService = agentService;
    this.sessionService = sessionService;
  }

  /**
   * 进入交互式对话，直到用户输入 {@code /quit} 或输入流结束。
   *
   * <p><b>输入流结束等同 {@code /quit}</b>（Edge Cases）：Ctrl+D 与管道读完都会让 {@code readLine()} 返回 {@code null}，
   * 此时正常退出、退出码 0——把它当错误会让「{@code echo "你好" | oryxos chat}」这种用法变成失败。
   *
   * <p><b>空行跳过</b>：不交给引擎，也不产生一条空消息。空消息会白白消耗一次模型调用，还会在历史里留一条无意义记录。
   *
   * @param profileName 本次对话使用的 Agent 名；存在性已由调用方（Agent 解析闸门）校验过
   */
  public void run(String profileName) {
    Session session = sessionService.getOrCreate(CHANNEL, LOCAL_USER, profileName);
    System.out.println(
        "已连接 Agent '" + profileName + "'（channel=" + CHANNEL + ", user=" + LOCAL_USER + "）");
    System.out.println("输入内容开始对话，输入 " + QUIT + " 退出。");
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    while (true) {
      System.out.print(PROMPT);
      System.out.flush();
      String line = readLine(reader);
      if (line == null) {
        // 输入流结束：补一个换行，避免 shell 提示符贴在本行的 "> " 后面
        System.out.println();
        return;
      }
      String message = line.trim();
      if (QUIT.equals(message)) {
        return;
      }
      if (message.isEmpty()) {
        continue;
      }
      System.out.println(agentService.process(session, message));
    }
  }

  /**
   * 单条模式：处理一条消息、打印答复、返回（FR-005）。<b>不进交互循环</b>。
   *
   * @param profileName 本次使用的 Agent 名
   * @param message 用户输入
   */
  public void send(String profileName, String message) {
    Session session = sessionService.getOrCreate(CHANNEL, LOCAL_USER, profileName);
    System.out.println(agentService.process(session, message));
  }

  private static String readLine(BufferedReader reader) {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new IllegalStateException("读取输入失败：" + e.getMessage(), e);
    }
  }
}
