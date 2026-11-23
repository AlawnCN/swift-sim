#!/usr/bin/env bash
# Start swift-sim in the background.
#   Release package (has PACKAGE.txt): bundled Java when present, listen on 0.0.0.0:8080/8081/8082.
#   Source checkout: target/swift-sim-*.jar, application.yml (127.0.0.1) unless you pass addresses.
# Logs: <package>/data/app.log    PID: <package>/data/app.pid
# Extra arguments are passed to the jar after the defaults, so they win.
set -u

ROOT=$(cd "$(dirname "$0")" && pwd)

die() { echo "swift-sim: $*" >&2; exit 1; }

read_package_field() {
  local key=$1
  [[ -f "$ROOT/PACKAGE.txt" ]] || return 0
  local line
  line=$(grep -E "^${key}=" "$ROOT/PACKAGE.txt" | tail -n 1 || true)
  printf '%s' "${line#*=}"
}

java_major() {
  local bin=$1 out
  out=$("$bin" -version 2>&1 || true)
  local ver
  ver=$(printf '%s\n' "$out" | awk -F\" '/version/ { print $2; exit }')
  ver=${ver%%.*}
  if [[ "$ver" == "1" ]]; then ver=$(printf '%s\n' "$out" | awk -F\" '/version/ { split($2, a, "."); print a[2]; exit }'); fi
  printf '%s' "$ver"
}

pick_java() {
  local mode major
  mode=$(read_package_field java)
  if [[ -n "${SIM_JAVA:-}" ]]; then
    [[ -x "$SIM_JAVA" ]] || die "SIM_JAVA 不可执行: $SIM_JAVA"
    major=$(java_major "$SIM_JAVA")
    [[ "$major" =~ ^[0-9]+$ && "$major" -ge 21 ]] || die "SIM_JAVA 需要 Java 21 或更高，当前主版本是 '${major:-未知}'"
    printf '%s' "$SIM_JAVA"
    return
  fi
  if [[ -x "$ROOT/runtime/bin/java" ]]; then
    major=$(java_major "$ROOT/runtime/bin/java")
    [[ "$major" =~ ^[0-9]+$ && "$major" -ge 21 ]] || die "包内 runtime/bin/java 不是 Java 21（主版本 '${major:-未知}'）。请使用与 PACKAGE.txt 中系统架构一致的发行包。"
    printf '%s' "$ROOT/runtime/bin/java"
    return
  fi
  if [[ "$mode" == "bundled" ]]; then
    die "这是带 Java 运行时的发行包，但 $ROOT/runtime/bin/java 不存在或不可执行。请重新解压完整的 tar.gz，不要改用系统里的 Java。"
  fi
  local candidate=""
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    candidate="$JAVA_HOME/bin/java"
  elif [[ -x /opt/tools/jdk/bin/java ]]; then
    candidate=/opt/tools/jdk/bin/java
  elif command -v java >/dev/null 2>&1; then
    candidate=$(command -v java)
  fi
  if [[ -z "$candidate" ]]; then
    if [[ "$mode" == "requires-java-21" ]]; then
      die "这是精简包，不含 Java。请安装 Java 21 并设置 JAVA_HOME，或改用带 runtime 的 linux-x86_64 发行包。"
    fi
    die "找不到 Java。源码运行需要本机 JDK（建议 21），或设置 JAVA_HOME。"
  fi
  if [[ "$mode" == "requires-java-21" ]]; then
    major=$(java_major "$candidate")
    [[ "$major" =~ ^[0-9]+$ && "$major" -ge 21 ]] || die "精简包需要 Java 21 或更高。当前 $candidate 的主版本是 '${major:-未知}'。"
  fi
  printf '%s' "$candidate"
}

find_jar() {
  if [[ -f "$ROOT/lib/swift-sim.jar" ]]; then
    printf '%s' "$ROOT/lib/swift-sim.jar"
    return
  fi
  local jar
  jar=$(ls -1 "$ROOT"/target/swift-sim-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
  [[ -n "$jar" ]] || die "找不到可执行 jar。发行包应有 lib/swift-sim.jar；源码目录请先 mvn -B package，生成 target/swift-sim-*.jar。"
  printf '%s' "$jar"
}

is_our_process() {
  local pid=$1 jar=$2 cmd
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  [[ -r "/proc/$pid/cmdline" ]] || return 1
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
  [[ "$cmd" == *"$jar"* ]]
}

port_open() {
  local port=$1 hex
  hex=$(printf '%04X' "$port")
  if [[ -r /proc/net/tcp ]]; then
    awk -v suf=":${hex}" 'NR>1 && $4=="0A" && substr($2, length($2)-length(suf)+1)==suf { found=1 } END { exit found?0:1 }' \
      /proc/net/tcp /proc/net/tcp6 2>/dev/null && return 0
    return 1
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltn | awk '{print $4}' | grep -Eq "(^|[.:])${port}$" && return 0
    return 1
  fi
  (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1
}

http_ok() {
  local url=$1
  if command -v curl >/dev/null 2>&1; then
    curl -sf -m 2 -o /dev/null "$url"
    return
  fi
  if command -v wget >/dev/null 2>&1; then
    wget -q -T 2 -O /dev/null "$url"
    return
  fi
  return 1
}

release_mode=0
[[ -f "$ROOT/PACKAGE.txt" ]] && release_mode=1

JAVA=$(pick_java) || exit 1
JAR=$(find_jar) || exit 1
PIDF="$ROOT/data/app.pid"
LOG="$ROOT/data/app.log"
mkdir -p "$ROOT/data"

if [[ -f "$PIDF" ]]; then
  old=$(tr -d '[:space:]' < "$PIDF" || true)
  if [[ -n "$old" ]] && kill -0 "$old" 2>/dev/null; then
    if is_our_process "$old" "$JAR"; then
      echo "已经在运行，pid $old（不会再启动第二个进程）"
      echo "  日志: $LOG"
      exit 0
    fi
    die "data/app.pid 指向仍在运行的其他进程 $old，不是本目录的模拟器。没有停止它。请确认后删除这个 pid 文件再启动。"
  fi
  rm -f "$PIDF"
fi

port=8080
admin_port=8081
ai_port=8082
bind=""
admin_bind=""
ai_bind=""
user_port=0 user_admin_port=0 user_ai_port=0
user_bind=0 user_admin_bind=0 user_ai_bind=0
for arg in "$@"; do
  case "$arg" in
    --server.port=*) port=${arg#*=}; user_port=1 ;;
    --sim.admin.port=*) admin_port=${arg#*=}; user_admin_port=1 ;;
    --sim.ai.port=*) ai_port=${arg#*=}; user_ai_port=1 ;;
    --server.address=*) bind=${arg#*=}; user_bind=1 ;;
    --sim.admin.address=*) admin_bind=${arg#*=}; user_admin_bind=1 ;;
    --sim.ai.address=*) ai_bind=${arg#*=}; user_ai_bind=1 ;;
  esac
done
if [[ "$user_port" -eq 0 && -n "${SIM_PORT:-}" ]]; then port=$SIM_PORT; fi
if [[ "$user_admin_port" -eq 0 && -n "${SIM_ADMIN_PORT:-}" ]]; then admin_port=$SIM_ADMIN_PORT; fi
if [[ "$user_ai_port" -eq 0 && -n "${SIM_AI_PORT:-}" ]]; then ai_port=$SIM_AI_PORT; fi
if [[ "$user_bind" -eq 0 && -n "${SIM_BIND_ADDRESS:-}" ]]; then bind=$SIM_BIND_ADDRESS; fi
if [[ "$user_admin_bind" -eq 0 && -n "${SIM_ADMIN_BIND:-}" ]]; then admin_bind=$SIM_ADMIN_BIND; fi
if [[ "$user_ai_bind" -eq 0 && -n "${SIM_AI_BIND:-}" ]]; then ai_bind=$SIM_AI_BIND; fi
if [[ "$release_mode" -eq 1 ]]; then
  [[ -n "$bind" ]] || bind=0.0.0.0
  [[ -n "$admin_bind" ]] || admin_bind=$bind
  [[ -n "$ai_bind" ]] || ai_bind=$bind
fi

for p in "$port" "$admin_port" "$ai_port"; do
  if port_open "$p"; then
    die "端口 $p 已被占用。请换端口（例如 ./run.sh --server.port=18080 --sim.admin.port=18081 --sim.ai.port=18082），或停掉占用该端口的程序。没有启动新进程。"
  fi
done

defaults=()
if [[ "$release_mode" -eq 1 || -n "$bind" ]]; then
  [[ "$user_bind" -eq 1 ]] || defaults+=(--server.address="$bind")
fi
if [[ "$release_mode" -eq 1 || -n "$admin_bind" ]]; then
  [[ "$user_admin_bind" -eq 1 ]] || defaults+=(--sim.admin.address="$admin_bind")
fi
if [[ "$release_mode" -eq 1 || -n "$ai_bind" ]]; then
  [[ "$user_ai_bind" -eq 1 ]] || defaults+=(--sim.ai.address="$ai_bind")
fi
[[ "$user_port" -eq 0 && -n "${SIM_PORT:-}" ]] && defaults+=(--server.port="$port")
[[ "$user_admin_port" -eq 0 && -n "${SIM_ADMIN_PORT:-}" ]] && defaults+=(--sim.admin.port="$admin_port")
[[ "$user_ai_port" -eq 0 && -n "${SIM_AI_PORT:-}" ]] && defaults+=(--sim.ai.port="$ai_port")

cd "$ROOT"
nohup "$JAVA" -Xms128m -Xmx512m -Djava.net.preferIPv4Stack=true -jar "$JAR" \
  --sim.data-dir="$ROOT/data" \
  --sim.config-dir="$ROOT/config" \
  --sim.samples-dir="$ROOT/samples" \
  --sim.secrets-dir="$ROOT/config/secrets" \
  "${defaults[@]}" \
  "$@" >> "$LOG" 2>&1 &
echo $! > "$PIDF"
pid=$(cat "$PIDF")

ready_host=127.0.0.1
if [[ -n "$bind" && "$bind" != "0.0.0.0" && "$bind" != "127.0.0.1" && "$bind" != "localhost" ]]; then
  ready_host=$bind
fi
ok=0
for _ in $(seq 1 60); do
  if ! kill -0 "$pid" 2>/dev/null; then
    break
  fi
  if http_ok "http://${ready_host}:${port}/api/participants"; then
    ok=1
    break
  fi
  sleep 1
done

if [[ "$ok" -ne 1 ]]; then
  if kill -0 "$pid" 2>/dev/null && is_our_process "$pid" "$JAR"; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PIDF"
  echo "启动失败。日志最后几行（$LOG）：" >&2
  tail -n 40 "$LOG" >&2 || true
  die "进程没有在 ${ready_host}:${port} 上就绪。若日志里是 Address already in use，说明端口冲突。"
fi

shown_bind=${bind:-（配置文件，源码默认 127.0.0.1）}
echo "已启动 pid $pid"
echo "  监听: $shown_bind"
echo "  网关 / API / 页面 : http://${ready_host}:${port}/"
echo "  管理端            : http://${ready_host}:${admin_port}/    账号文件: $ROOT/config/secrets/admin-console.properties"
echo "  AI API            : http://${ready_host}:${ai_port}/ai/v1   密钥文件: $ROOT/config/secrets/ai-api-keys"
echo "  日志: $LOG"
if [[ "$release_mode" -eq 1 && "$bind" == "0.0.0.0" ]]; then
  echo "  局域网: 用本机 IP 替换上面的地址。8080 没有登录鉴权，只应在可信局域网开放，并用防火墙限制来源。不要做公网隧道。"
  echo "  文件传输默认 HTTP。示例: ./run.sh --sim.transport.mode=BOTH --sim.transport.file.root=/path/to/kenex"
fi
