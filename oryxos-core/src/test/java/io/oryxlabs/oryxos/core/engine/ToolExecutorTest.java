package io.oryxlabs.oryxos.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.oryxlabs.oryxos.core.tool.OryxTool;
import io.oryxlabs.oryxos.core.tool.ToolCatalog;
import io.oryxlabs.oryxos.core.tool.ToolResult;
import io.oryxlabs.oryxos.storage.entity.ToolInvocation;
import io.oryxlabs.oryxos.storage.repository.ToolInvocationRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * {@link ToolExecutor} 的验收测试（课件第 17 节「四、验收 harness」）。
 *
 * <p>守护的三条不变量：每一次工具调用<b>恰好产生一行审计</b>（成功 / 失败 / 查不到工具都算）；每一次工具调用<b>恰好输出一条结构化
 * 日志</b>（章程附加约束「可观测」要求日志覆盖每一次 Tool 调用，只记失败不满足）；工具异常<b>不打断循环</b>（FR-007）。
 *
 * <p>每个用例都必须断言「审计记录被写过」——只断言返回值等于放跑了本节唯一的交付物。
 */
class ToolExecutorTest {

  private static final String SESSION_ID = "cli:alice:demo";

  private ToolCatalog toolCatalog;
  private ToolInvocationRepository repository;
  private ToolExecutor executor;

  @BeforeEach
  void setUp() {
    toolCatalog = mock(ToolCatalog.class);
    repository = mock(ToolInvocationRepository.class);
    executor = new ToolExecutor(toolCatalog, repository);
  }

  @Test
  @DisplayName("成功：落一条 success 为真的记录")
  void recordsSuccessfulInvocation() {
    when(toolCatalog.find("http_get"))
        .thenReturn(Optional.of(toolReturning(ToolResult.success("晴，26 度"))));

    ToolResult result =
        executor.execute(SESSION_ID, call("call-1", "http_get", "{\"city\":\"上海\"}"));

    assertThat(result.success()).isTrue();
    assertThat(result.content()).isEqualTo("晴，26 度");

    ToolInvocation recorded = capturedInvocation();
    assertThat(recorded.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(recorded.getToolName()).isEqualTo("http_get");
    assertThat(recorded.getInputJson()).isEqualTo("{\"city\":\"上海\"}");
    assertThat(recorded.getSuccess()).isTrue();
    assertThat(recorded.getResultJson()).isEqualTo("晴，26 度");
    assertThat(recorded.getErrorMessage()).isNull();
    assertThat(recorded.getDurationMs()).isNotNull().isNotNegative();
    assertThat(recorded.getCreatedAt()).isNotNull();
  }

  @Test
  @DisplayName("工具抛异常：落一条 success 为假、原因含异常文本的记录")
  void recordsFailedInvocationWithReason() {
    when(toolCatalog.find("http_get"))
        .thenReturn(Optional.of(toolThrowing(new IllegalStateException("上游 503"))));

    ToolResult result = executor.execute(SESSION_ID, call("call-1", "http_get", "{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("上游 503");

    ToolInvocation recorded = capturedInvocation();
    assertThat(recorded.getSuccess()).isFalse();
    assertThat(recorded.getErrorMessage()).contains("上游 503");
    assertThat(recorded.getResultJson()).isNull();
  }

  @Test
  @DisplayName("工具异常不打断循环：转成失败结果返回，不上抛")
  void toolExceptionDoesNotBreakLoop() {
    when(toolCatalog.find("shell"))
        .thenReturn(Optional.of(toolThrowing(new IllegalStateException("沙箱拒绝"))));

    ToolResult result =
        assertDoesNotThrow(() -> executor.execute(SESSION_ID, call("call-1", "shell", "{}")));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("沙箱拒绝");
    verify(repository).save(any(ToolInvocation.class));
  }

  @Test
  @DisplayName("查不到工具：同样落账，原因写明工具名")
  void unknownToolIsRecordedAsFailure() {
    when(toolCatalog.find("no_such_tool")).thenReturn(Optional.empty());

    ToolResult result = executor.execute(SESSION_ID, call("call-1", "no_such_tool", "{}"));

    assertThat(result.success()).isFalse();
    assertThat(result.errorMessage()).contains("no_such_tool");

    ToolInvocation recorded = capturedInvocation();
    assertThat(recorded.getSuccess()).isFalse();
    assertThat(recorded.getToolName()).isEqualTo("no_such_tool");
    assertThat(recorded.getErrorMessage()).contains("no_such_tool");
  }

  @Test
  @DisplayName("每次工具调用都输出结构化日志：成功与失败各一条")
  void logsEveryInvocationOnBothPaths() {
    when(toolCatalog.find("http_get"))
        .thenReturn(Optional.of(toolReturning(ToolResult.success("晴"))));
    when(toolCatalog.find("shell"))
        .thenReturn(Optional.of(toolThrowing(new IllegalStateException("沙箱拒绝"))));

    Logger executorLogger = (Logger) LoggerFactory.getLogger(ToolExecutor.class);
    ListAppender<ILoggingEvent> successAppender = attach(executorLogger);
    try {
      executor.execute(SESSION_ID, call("call-1", "http_get", "{}"));
    } finally {
      executorLogger.detachAppender(successAppender);
    }

    ListAppender<ILoggingEvent> failureAppender = attach(executorLogger);
    try {
      executor.execute(SESSION_ID, call("call-2", "shell", "{}"));
    } finally {
      executorLogger.detachAppender(failureAppender);
    }

    assertThat(successAppender.list).as("成功路径必须恰好输出一条结构化日志").hasSize(1);
    assertThat(failureAppender.list).as("失败路径必须恰好输出一条结构化日志").hasSize(1);
  }

  private static ListAppender<ILoggingEvent> attach(Logger logger) {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private ToolInvocation capturedInvocation() {
    ArgumentCaptor<ToolInvocation> captor = ArgumentCaptor.forClass(ToolInvocation.class);
    verify(repository).save(captor.capture());
    return captor.getValue();
  }

  private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
    return new AssistantMessage.ToolCall(id, "function", name, arguments);
  }

  private static OryxTool toolReturning(ToolResult result) {
    return toolExecuting(inputJson -> result);
  }

  private static OryxTool toolThrowing(RuntimeException failure) {
    return toolExecuting(
        inputJson -> {
          throw failure;
        });
  }

  private static OryxTool toolExecuting(java.util.function.Function<String, ToolResult> behavior) {
    return new OryxTool() {
      @Override
      public String getName() {
        return "stub";
      }

      @Override
      public String getDescription() {
        return "测试替身";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(String inputJson) {
        return behavior.apply(inputJson);
      }
    };
  }
}
