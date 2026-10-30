#!/usr/bin/env bash
# Start / stop a throw-away simulator instance for the e2e tests, so the live instance (8080, ./data) is never touched.
#   tests/temp-instance.sh start|stop|restart|wipe      (env: SIM_TEST_PORT=8090, SIM_TEST_ADMIN_PORT=8091, SIM_TEST_AI_PORT=8092, SIM_TEST_DATA=/tmp/swift-sim-e2e)
#   SIM_URL=http://127.0.0.1:8090 SIM_DATA_DIR=/tmp/swift-sim-e2e \
#   SIM_STOP_CMD="tests/temp-instance.sh stop" SIM_START_CMD="tests/temp-instance.sh start" python3 tests/e2e.py
cd "$(dirname "$0")/.."
PORT=${SIM_TEST_PORT:-8090}
ADMIN_PORT=${SIM_TEST_ADMIN_PORT:-8091}
AI_PORT=${SIM_TEST_AI_PORT:-8092}
DATA=${SIM_TEST_DATA:-/tmp/swift-sim-e2e}
JAVA=${JAVA_HOME:-/opt/tools/jdk}/bin/java
[ -x "$JAVA" ] || JAVA=java
PIDF="$DATA/app.pid"
stop() {
  if [ -f "$PIDF" ]; then
    PID=$(cat "$PIDF")
    if kill "$PID" 2>/dev/null; then
      for i in $(seq 1 60); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
      kill -0 "$PID" 2>/dev/null && kill -9 "$PID" 2>/dev/null
      echo "stopped test instance $PID"
    fi
    rm -f "$PIDF"
  fi
}
start() {
  mkdir -p "$DATA"
  if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then echo "test instance already running"; return; fi
  [ "$DATA" -ef ./data ] && { echo "refusing to use the live data directory"; exit 1; }
  nohup "$JAVA" -Xms128m -Xmx512m -jar target/swift-sim-0.1.0.jar --server.port="$PORT" --sim.admin.port="$ADMIN_PORT" --sim.ai.port="$AI_PORT" --sim.data-dir="$DATA" --sim.secrets-dir="$DATA/secrets" >> "$DATA/app.log" 2>&1 &
  echo $! > "$PIDF"
  for i in $(seq 1 60); do curl -s -m 2 -o /dev/null "http://127.0.0.1:$PORT/api/participants" && break; sleep 1; done
  echo "test instance pid $(cat "$PIDF") -> http://127.0.0.1:$PORT/ admin http://127.0.0.1:$ADMIN_PORT/ ai http://127.0.0.1:$AI_PORT/ai/v1 (data $DATA, secrets $DATA/secrets)"
}
case "$1" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  wipe) stop; case "$DATA" in /tmp/*) rm -rf "$DATA" ;; *) echo "not wiping $DATA (outside /tmp)";; esac ;;
  *) echo "usage: $0 start|stop|restart|wipe"; exit 2 ;;
esac
