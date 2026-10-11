package io.oryxlabs.oryxos.tool.sandbox;

/**
 * 沙箱受控动作类型（技术方案 6.7）。
 *
 * <p>文件读写分开建模，便于未来按读/写分权限。
 */
public enum ActionType {

  /** 读文件。 */
  FILE_READ,

  /** 写文件。 */
  FILE_WRITE,

  /** 执行 Shell 命令。 */
  SHELL_COMMAND,

  /** 发起 HTTP 请求。 */
  HTTP_REQUEST
}
