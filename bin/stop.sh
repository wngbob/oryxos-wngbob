#!/usr/bin/env bash
#
# 停止 bin/start.sh 启动的 OryxOS 进程。
#
#   bin/stop.sh
#
# 先发 SIGTERM 让 Spring 正常关闭（关闭上下文、停容器），30 秒未退出再 SIGKILL。
# PID 来自 bin/oryxos.pid；PID 文件不存在或已过期时按「未在运行」处理，退出码 0。
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$BASE_DIR/bin/oryxos.pid"

is_oryxos_pid() {
  local pid="$1"
  [[ -r "/proc/$pid/cmdline" ]] || return 0
  tr '\0' ' ' <"/proc/$pid/cmdline" | grep -q 'oryxos.jar'
}

if [[ ! -f "$PID_FILE" ]]; then
  echo "OryxOS 未在运行（没有 $PID_FILE）"
  exit 0
fi

PID="$(cat "$PID_FILE" 2>/dev/null || true)"
if [[ -z "$PID" ]] || ! kill -0 "$PID" 2>/dev/null; then
  echo "OryxOS 未在运行（PID 文件已过期：${PID:-空}）"
  rm -f "$PID_FILE"
  exit 0
fi

if ! is_oryxos_pid "$PID"; then
  echo "错误：PID $PID 不是 OryxOS 进程，拒绝结束（PID 文件可能已过期）。" >&2
  echo "确认后手工执行：kill $PID   然后删除 $PID_FILE" >&2
  exit 1
fi

echo "正在停止 OryxOS（PID $PID）..."
kill -TERM "$PID"

for _ in $(seq 1 30); do
  if ! kill -0 "$PID" 2>/dev/null; then
    break
  fi
  sleep 1
done

if kill -0 "$PID" 2>/dev/null; then
  echo "30 秒内未退出，强制结束（SIGKILL）..."
  kill -KILL "$PID" 2>/dev/null || true
fi

rm -f "$PID_FILE"
echo "已停止。"
