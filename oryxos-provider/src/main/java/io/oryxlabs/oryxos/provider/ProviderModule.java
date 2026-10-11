package io.oryxlabs.oryxos.provider;

/**
 * oryxos-provider —— 能力一（对接 LLM）。
 *
 * <p>规划内容：{@code ProviderService}、Function Calling 适配、provider name → ChatModel 显式映射 （禁止按类型扫描
 * Bean）。Spring AI Alibaba 仅用协议转换 + {@code @Tool} schema 生成， 依赖在 US-1 落地时引入。
 *
 * <p>Maven 骨架占位类，标识模块与包结构；具体实现随 user story 落地（见 CLAUDE.md 实施路线）。
 */
public final class ProviderModule {

  public static final String NAME = "oryxos-provider";

  private ProviderModule() {}
}
