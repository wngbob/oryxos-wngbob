package io.oryxlabs.oryxos.web.controller.dto;

import java.util.Map;

/**
 * 保存一组文件的请求体（第 30 节 FR-005，contracts/agent-lifecycle-api.md §2.10）。
 *
 * <p>键是<b>相对 Agent 目录</b> 的路径（{@code AGENT.md} / {@code REFERENCE.md} / {@code
 * scripts/x.py}），值是该文件的完整内容。 必须含 {@code AGENT.md}——它是这个 Agent 的唯一真相源，没有它这次保存就无从校验，也就无从生效。
 *
 * @param files 相对路径 → 内容
 */
public record SaveFilesRequest(Map<String, String> files) {

  /**
   * 防御性拷贝：请求体是 Jackson 反序列化出来的可变 Map，直接透给编排层等于让它拿着一个谁都能改的引用。
   *
   * <p>与 {@code SessionView} 的归一化同一口径；顺带把 {@code null} 收成 {@code null}（控制器先判空再数落，保持「缺字段」与「空清单」可区分）。
   */
  public SaveFilesRequest {
    files = files == null ? null : Map.copyOf(files);
  }
}
