package io.oryxlabs.oryxos.tool.sandbox;

/**
 * 一次待校验的受控动作（技术方案 6.7：{@code SandboxAction = { type: ActionType, target: String }}）。
 *
 * @param type 动作类型
 * @param target 动作目标：文件路径 / 可执行文件 / URL / {@code host:port}，语义随 {@code type} 而定
 */
public record SandboxAction(ActionType type, String target) {}
