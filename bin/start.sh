#!/usr/bin/env bash
#
# OryxOS 后台启动脚本（server + manager）
#
#   bin/start.sh                          # 用 bin/config.yaml，后台启动
#   bin/start.sh --config /path/cfg.yaml  # 指定配置文件
#   bin/start.sh --port 9090              # 覆盖配置里的端口
#   bin/start.sh --foreground             # 前台启动（调试用，Ctrl+C 退出）
#
# 一个进程同时提供两样东西——管理台（manager）没有独立后端：
#   REST 接口  http://localhost:<port>/api/v1
#   管理台     http://localhost:<port>/admin/
#   接口文档   http://localhost:<port>/swagger-ui
#
# DeepSeek 的 key 从 YAML 配置文件读取，以 DEEPSEEK_API_KEY 环境变量交给进程
# （application.yaml 里是 ${DEEPSEEK_API_KEY} 占位，明文凭证不进仓库）。
#
# 运行时产物（都在 bin/ 下，已 gitignore）：
#   oryxos.pid  进程号
#   oryxos.log  控制台输出
set -euo pipefail

BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="$BASE_DIR/bin/oryxos.pid"
LOG_FILE="$BASE_DIR/bin/oryxos.log"

usage() {
  cat <<'EOF'
用法: bin/start.sh [--config <file>] [--port <port>] [--foreground]

  --config <file>   启动配置（默认 bin/config.yaml，也可用环境变量 ORYXOS_CONFIG）
  --port <port>     覆盖配置里的 server.port
  --foreground, -f  前台运行，Ctrl+C 退出（默认后台运行）
  -h, --help        显示本帮助

配置文件模板：bin/config.yaml.example
EOF
}

CONFIG_ARG=""
PORT_ARG=""
FOREGROUND=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      [[ $# -ge 2 ]] || { echo "错误：--config 缺参数值" >&2; exit 2; }
      CONFIG_ARG="$2"
      shift 2
      ;;
    --port)
      [[ $# -ge 2 ]] || { echo "错误：--port 缺参数值" >&2; exit 2; }
      PORT_ARG="$2"
      shift 2
      ;;
    --foreground | -f)
      FOREGROUND=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "错误：未知参数 '$1'" >&2
      usage >&2
      exit 2
      ;;
  esac
done

CONFIG="${CONFIG_ARG:-${ORYXOS_CONFIG:-$BASE_DIR/bin/config.yaml}}"
JAR="${ORYXOS_JAR:-$BASE_DIR/oryxos-boot/target/oryxos.jar}"

if [[ ! -f "$CONFIG" ]]; then
  echo "错误：找不到配置文件 $CONFIG" >&2
  echo "先复制模板并填入 DeepSeek API key：" >&2
  echo "  cp $BASE_DIR/bin/config.yaml.example $CONFIG" >&2
  exit 1
fi

if [[ ! -f "$JAR" ]]; then
  echo "错误：找不到 $JAR" >&2
  echo "先构建（注意必须带 clean，否则依赖变更会打出旧字节码）：" >&2
  echo "  cd $BASE_DIR && ./mvnw clean package -DskipTests -Ddependency-check.skip=true" >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="$(command -v java || true)"
fi
if [[ -z "$JAVA" ]]; then
  echo "错误：找不到 java。设置 JAVA_HOME，或把 JDK 21 的 bin 放进 PATH。" >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "错误：解析 YAML 需要 python3。" >&2
  exit 1
fi

# 解析配置文件的「两级键 + 标量值」子集，输出 KEY=VALUE 行。
# 只认 YAML 的最小形状，不做类型推断——端口与 key 都是字符串，交给调用方校验。
CFG_LINES="$(python3 - "$CONFIG" <<'PY'
import re
import sys


def strip_comment(value):
    quote = None
    for index, char in enumerate(value):
        if quote:
            if char == quote:
                quote = None
        elif char in ("'", '"'):
            quote = char
        elif char == "#" and (index == 0 or value[index - 1].isspace()):
            return value[:index]
    return value


def unquote(value):
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


path = sys.argv[1]
section = ""
out = []
try:
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = re.match(r"^(\s*)([A-Za-z0-9_.-]+)\s*:\s*(.*)$", line)
            if not match:
                continue
            indent, key, value = match.group(1), match.group(2), match.group(3)
            value = unquote(strip_comment(value).strip())
            if value == "":
                section = key if not indent else ""
                continue
            out.append((section + "." + key if section else key) + "=" + value)
except OSError as error:
    sys.stderr.write("读取配置失败: %s\n" % error)
    sys.exit(2)
print("\n".join(out))
PY
)"

get_cfg() {
  local key="$1" line
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if [[ "${line%%=*}" == "$key" ]]; then
      printf '%s' "${line#*=}"
      return 0
    fi
  done <<<"$CFG_LINES"
  return 1
}

# DeepSeek key：配置文件优先；留空时退回当前 shell 的环境变量。
API_KEY="$(get_cfg "deepseek.api-key" || true)"
if [[ -z "$API_KEY" ]]; then
  API_KEY="${DEEPSEEK_API_KEY:-}"
fi
if [[ -z "$API_KEY" ]]; then
  echo "错误：DeepSeek API key 未配置。" >&2
  echo "在 $CONFIG 里填 deepseek.api-key，或先 export DEEPSEEK_API_KEY=..." >&2
  exit 1
fi
# 子进程只认环境变量；配置值在这里进环境，不写进任何仓库文件。
export DEEPSEEK_API_KEY="$API_KEY"

PORT="${PORT_ARG:-$(get_cfg "server.port" || true)}"
PORT="${PORT:-8080}"
if [[ ! "$PORT" =~ ^[0-9]+$ ]] || ((PORT < 1 || PORT > 65535)); then
  echo "错误：端口非法 '$PORT'（应为 1~65535 的整数）" >&2
  exit 1
fi

is_oryxos_pid() {
  local pid="$1"
  [[ -r "/proc/$pid/cmdline" ]] || return 0
  tr '\0' ' ' <"/proc/$pid/cmdline" | grep -q 'oryxos.jar'
}

if [[ -f "$PID_FILE" ]]; then
  EXISTING="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "$EXISTING" ]] && kill -0 "$EXISTING" 2>/dev/null; then
    if is_oryxos_pid "$EXISTING"; then
      echo "OryxOS 已在运行（PID $EXISTING）。停止：$BASE_DIR/bin/stop.sh"
      exit 0
    fi
    echo "提示：PID 文件里的 $EXISTING 不是 OryxOS 进程（PID 可能被复用），忽略并覆盖。" >&2
  fi
  rm -f "$PID_FILE"
fi

# 端口预检：被占用时直接说清楚，不要等 Spring 起完再抛 "Port already in use"。
# /dev/tcp 是 bash 内建；该检查失败（不支持或连接被拒）都按「端口空闲」继续。
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  exec 3<&- 3>&-
  echo "错误：端口 $PORT 已被占用。" >&2
  echo "先停掉占用它的进程，或用 --port 换一个端口。" >&2
  exit 1
fi

cd "$BASE_DIR"

# serve 不建表：schema 由 init 建，缺列也由 init 补齐（幂等，已存在的目录和文件不覆盖）。
echo "检查工作区与表结构（oryxos init，幂等）..."
"$JAVA" -jar "$JAR" init >>"$LOG_FILE" 2>&1 || {
  echo "错误：oryxos init 失败，日志尾部：" >&2
  tail -n 20 "$LOG_FILE" >&2
  exit 1
}

if [[ $FOREGROUND -eq 1 ]]; then
  echo "前台启动（日志直接打到当前终端，Ctrl+C 退出）..."
  exec "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" serve --port "$PORT"
fi

echo "后台启动..."
nohup "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" serve --port "$PORT" >>"$LOG_FILE" 2>&1 &
CHILD=$!
echo "$CHILD" >"$PID_FILE"

HEALTH_URL="http://127.0.0.1:$PORT/api/v1/health"
READY=0
if command -v curl >/dev/null 2>&1; then
  for _ in $(seq 1 60); do
    if ! kill -0 "$CHILD" 2>/dev/null; then
      break
    fi
    if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
      READY=1
      break
    fi
    sleep 1
  done
else
  sleep 5
  if kill -0 "$CHILD" 2>/dev/null; then
    READY=1
  fi
fi

if [[ $READY -ne 1 ]]; then
  echo "错误：OryxOS 未能在 60 秒内就绪。日志尾部：" >&2
  kill -0 "$CHILD" 2>/dev/null && kill "$CHILD" 2>/dev/null || true
  rm -f "$PID_FILE"
  tail -n 40 "$LOG_FILE" >&2
  exit 1
fi

ADMIN_STATUS=""
if command -v curl >/dev/null 2>&1; then
  ADMIN_STATUS="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$PORT/admin/" || true)"
fi

cat <<EOF
OryxOS 已启动
  PID        $CHILD
  REST 接口  http://localhost:$PORT/api/v1
  管理台     http://localhost:$PORT/admin/
  接口文档   http://localhost:$PORT/swagger-ui
  日志       $LOG_FILE
  停止       $BASE_DIR/bin/stop.sh
EOF

if [[ -n "$ADMIN_STATUS" && "$ADMIN_STATUS" != "200" ]]; then
  echo "" >&2
  echo "警告：/admin/ 返回 HTTP $ADMIN_STATUS——这份 jar 可能没打进管理台静态资源。" >&2
  echo "      用完整构建重打：cd $BASE_DIR && ./mvnw clean package -Ddependency-check.skip=true" >&2
  echo "      （不要加 -Dfrontend.skip=true）" >&2
fi
