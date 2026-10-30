#!/usr/bin/env bash
# Stop the simulator and wait (max 30 s) until the process has exited.
cd "$(dirname "$0")"
if [ -f data/app.pid ]; then
  PID=$(cat data/app.pid)
  if kill "$PID" 2>/dev/null; then
    for i in $(seq 1 60); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$PID" 2>/dev/null && kill -9 "$PID" 2>/dev/null
    echo "stopped $PID"
  fi
  rm -f data/app.pid
fi
