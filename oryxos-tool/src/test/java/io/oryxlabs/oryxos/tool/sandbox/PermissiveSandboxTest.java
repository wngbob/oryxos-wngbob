package io.oryxlabs.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link PermissiveSandbox} 的两条守点（SC-010）：警告恰好一条、四种动作都不拦。
 *
 * <p><b>为什么警告条数也要断言</b>：缓解措施 2 的全部价值在于「读日志的人一眼看到窗口期」。打零条等于没警告；打成一片会淹没在启动日志里。 恰好一条是这两个坏法之间唯一可判定的位置。
 *
 * <p><b>为什么放构造函数而不是装配方法</b>：本测试<b>根本不起 Spring 上下文</b>，直接 {@code
 * new}——这正是选择构造函数的原因（contracts/assembly.md §4）。放在装配方法里，这条测试就只能靠起上下文来验，且绕过装配的构造路径不会警告。
 */
class PermissiveSandboxTest {

  @Test
  @DisplayName("构造时恰好打一条 WARN，文案同时含「临时」与「第 24 节」")
  void constructorWarnsExactlyOnce() {
    ListAppender<ILoggingEvent> appender = attachAppender();

    new PermissiveSandbox();

    List<ILoggingEvent> warns =
        appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    assertThat(warns).hasSize(1);
    assertThat(warns.get(0).getFormattedMessage()).contains("临时").contains("第 24 节");

    detachAppender(appender);
  }

  @Test
  @DisplayName("四种动作类型一律放行，不抛异常")
  void enforceAllowsEveryActionType() {
    PermissiveSandbox sandbox = new PermissiveSandbox();

    // 逐类型过一遍：只测一种会漏掉「按 type 分支写错」的实现
    for (ActionType type : ActionType.values()) {
      sandbox.enforce(new SandboxAction(type, "/etc/shadow"));
    }
  }

  private static ListAppender<ILoggingEvent> attachAppender() {
    Logger logger = (Logger) LoggerFactory.getLogger(PermissiveSandbox.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachAppender(ListAppender<ILoggingEvent> appender) {
    ((Logger) LoggerFactory.getLogger(PermissiveSandbox.class)).detachAppender(appender);
  }
}
