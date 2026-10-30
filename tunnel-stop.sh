#!/usr/bin/env bash
# Stop the Cloudflare Tunnel connector.
cd "$(dirname "$0")"
if [ -f data/cloudflared.pid ]; then
  PID=$(cat data/cloudflared.pid)
  if kill "$PID" 2>/dev/null; then
    for i in $(seq 1 40); do kill -0 "$PID" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$PID" 2>/dev/null && kill -9 "$PID" 2>/dev/null
    echo "tunnel stopped $PID"
  fi
  rm -f data/cloudflared.pid
else
  echo "tunnel not running"
fi
