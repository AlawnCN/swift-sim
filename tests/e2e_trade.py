#!/usr/bin/env python3
"""Runs only the Stage 1-3 cases with the helpers of e2e.py (same env vars):
  tests/temp-instance.sh start && python3 tests/e2e_trade.py"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, "e2e.py")).read()
prefix = src.split("# ================================================================ A.")[0]
g = {"__file__": os.path.join(HERE, "e2e.py"), "__name__": "e2e_trade"}
exec(compile(prefix, "e2e.py", "exec"), g)
exec(compile(open(os.path.join(HERE, "e2e_trade_cases.py")).read(), "e2e_trade_cases.py", "exec"), g)
results = g["results"]
passed = sum(1 for r in results if r[1]); failed = [r for r in results if not r[1]]
print(f"\n{passed}/{len(results)} checks passed")
for r in failed: print("  FAILED:", r[0], "::", str(r[2])[:300])
sys.exit(0 if not failed else 1)
