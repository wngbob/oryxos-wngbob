package io.oryxlabs.oryxos.web.api;

import io.oryxlabs.oryxos.provider.ProviderUnavailableException;
import io.oryxlabs.oryxos.web.error.AgentTimeoutException;
import io.oryxlabs.oryxos.web.error.ProfileNotFoundException;
import io.oryxlabs.oryxos.web.error.SessionNotFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 异常出口判卷用的探针控制器：每个端点只负责抛一种异常，判卷范围因此只有「出口怎么翻译异常」。
 *
 * <p><b>为什么是顶层类而不是测试类里的 {@code static} 嵌套类</b>：Spring Boot 的 {@code TestTypeExcludeFilter}
 * 会把「外层是测试类的嵌套类」整类排除在组件扫描之外（实测：写成嵌套类时所有探针路径都落到静态资源处理器， 全部返回 {@code resource not found:
 * probe/...}）。放在测试源码里不进发行物，但必须与测试类同级成顶层类。
 */
@RestController
public class ProbeController {

  @GetMapping("/probe/ok")
  ApiResponse<String> ok() {
    return ApiResponse.ok("fine");
  }

  @GetMapping("/probe/illegal-argument")
  ApiResponse<Void> illegalArgument() {
    throw new IllegalArgumentException("bad input");
  }

  @GetMapping("/probe/illegal-state")
  ApiResponse<Void> illegalState() {
    throw new IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed");
  }

  @GetMapping("/probe/no-resource")
  ApiResponse<Void> noResource() throws NoResourceFoundException {
    // 这是个受检异常（ServletException 子类），探针方法必须显式声明
    throw new NoResourceFoundException(HttpMethod.GET, "/missing");
  }

  @GetMapping("/probe/session-not-found")
  ApiResponse<Void> sessionNotFound() {
    throw new SessionNotFoundException("web:alice:daily-weather");
  }

  @GetMapping("/probe/profile-not-found")
  ApiResponse<Void> profileNotFound() {
    throw new ProfileNotFoundException("ghost");
  }

  @GetMapping("/probe/provider-unavailable")
  ApiResponse<Void> providerUnavailable() {
    throw new ProviderUnavailableException(
        "deepseek", new IllegalStateException("connection reset by peer"));
  }

  @GetMapping("/probe/timeout")
  ApiResponse<Void> timeout() {
    throw new AgentTimeoutException();
  }
}
