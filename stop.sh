#!/usr/bin/env bash
# Stop only the simulator started from this directory. A second run is harmless.
# A stale pid file is removed. A live process that is not this install is left alone.
set -u

ROOT=$(cd "$(dirname "$0")" && pwd)
PIDF="$ROOT/data/app.pid"

find_jar() {
  if [[ -f "$ROOT/lib/swift-sim.jar" ]]; then
    printf '%s' "$ROOT/lib/swift-sim.jar"
    return
  fi
  local jar
  jar=$(ls -1 "$ROOT"/target/swift-sim-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
  printf '%s' "$jar"
}

is_our_process() {
  local pid=$1 jar=$2 cmd
  [[ -n "$jar" ]] || return 1
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  [[ -r "/proc/$pid/cmdline" ]] || return 1
  cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
  [[ "$cmd" == *"$jar"* ]]
}

if [[ ! -f "$PIDF" ]]; then
  echo "没有在运行（没有 pid 文件）"
  exit 0
fi

pid=$(tr -d '[:space:]' < "$PIDF" || true)
JAR=$(find_jar)

if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
  rm -f "$PIDF"
  echo "进程已不在，已清掉过期的 pid 文件"
  exit 0
fi

if ! is_our_process "$pid" "$JAR"; then
  echo "pid $pid 仍在运行，但不是本目录的 swift-sim（$JAR）。没有向它发信号，也没有删除 pid 文件。" >&2
  exit 0
fi

kill "$pid" 2>/dev/null || true
for _ in $(seq 1 60); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 0.5
done
if kill -0 "$pid" 2>/dev/null; then
  if is_our_process "$pid" "$JAR"; then
    kill -9 "$pid" 2>/dev/null || true
    sleep 0.2
  fi
fi
if kill -0 "$pid" 2>/dev/null && is_our_process "$pid" "$JAR"; then
  echo "没能停止 pid $pid。pid 文件仍保留。" >&2
  exit 1
fi
rm -f "$PIDF"
echo "已停止 $pid"
exit 0
