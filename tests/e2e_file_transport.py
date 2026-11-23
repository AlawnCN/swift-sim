#!/usr/bin/env python3
"""Acceptance test for payment-application folder transport.

Uses a throw-away simulator (not port 8080, not ./data). Start it first, for example:

  SIM_TEST_PORT=18180 SIM_TEST_ADMIN_PORT=18181 SIM_TEST_AI_PORT=18182 \\
  SIM_TEST_DATA=/tmp/swift-sim-file-e2e \\
  SIM_TEST_JAVA_ARGS="--sim.transport.mode=HTTP --sim.transport.file.root=/tmp/swift-sim-kenex-e2e --sim.transport.file.poll-ms=400 --sim.transport.file.stable-ms=0" \\
  tests/temp-instance.sh start

  SIM_URL=http://127.0.0.1:18180 SIM_ADMIN_URL=http://127.0.0.1:18181 \\
  SIM_DATA_DIR=/tmp/swift-sim-file-e2e KENEX=/tmp/swift-sim-kenex-e2e \\
  python3 tests/e2e_file_transport.py
"""
import json, os, shutil, subprocess, time, urllib.error, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("SIM_URL", "http://127.0.0.1:18180")
ADMIN = os.environ.get("SIM_ADMIN_URL", "http://127.0.0.1:18181")
DATA = Path(os.environ.get("SIM_DATA_DIR", "/tmp/swift-sim-file-e2e"))
KENEX = Path(os.environ.get("KENEX", "/tmp/swift-sim-kenex-e2e"))
SAMPLES = ROOT / "samples"
results = []

def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + ("" if ok or detail == "" else " :: " + str(detail)[:500]))

def req(base, method, path, body=None, headers=None, timeout=30):
    data = None
    h = dict(headers or {})
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        elif isinstance(body, str):
            data = body.encode()
            h.setdefault("Content-Type", "text/plain")
        else:
            data = body
    r = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw or b"null"), resp.headers
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            parsed = json.loads(raw or b"null")
        except Exception:
            parsed = raw.decode("utf-8", "replace")
        return e.code, parsed, e.headers

def gw(method, path, body=None):
    return req(BASE, method, path, body)

def admin_login():
    props = {}
    for line in (DATA / "secrets" / "admin-console.properties").read_text().splitlines():
        if "=" in line and not line.strip().startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    code, body, headers = req(ADMIN, "POST", "/admin/api/login", {"username": props["username"], "password": props["password"]})
    cookie = headers.get("Set-Cookie", "")
    token = cookie.split(";", 1)[0]
    if code != 200 or not token.startswith("SIMADMIN="):
        raise SystemExit(f"admin login failed {code} {body}")
    return {"Cookie": token, "X-Sim-Admin": "1"}

def names(path: Path):
    if not path.exists():
        return []
    return sorted(p.name for p in path.iterdir())

def read_tree_text(path: Path):
    out = {}
    if not path.exists():
        return out
    for p in path.rglob("*"):
        if p.is_file() and not p.is_symlink():
            out[str(p.relative_to(path))] = p.read_bytes()
    return out

def wait_until(label, pred, seconds=20):
    deadline = time.time() + seconds
    last = None
    while time.time() < deadline:
        last = pred()
        if last:
            return last
        time.sleep(0.4)
    record(label, False, last)
    return None

def main():
    out_mt = KENEX / "outgoing" / "mt"
    out_mx = KENEX / "outgoing" / "mx"
    in_mt = KENEX / "incoming" / "mt"
    in_mx = KENEX / "incoming" / "mx"
    temp_in = KENEX / "temp" / "incoming"
    code, parts, _ = gw("GET", "/api/participants")
    record("HTTP API answers while file transport is off", code == 200 and any(p.get("sut") for p in parts), code)

    # HTTP mode must not touch the tree the test prepared.
    time.sleep(2.0)
    record("HTTP mode leaves outgoing MT in place", (out_mt / "MT700PYHNHFCY3PUDF7S.txt").is_file(), names(out_mt))
    record("HTTP mode does not create simulator state", not (KENEX / ".swiftsim").exists(), KENEX)
    record("HTTP mode does not consume temp/incoming", (temp_in / "sentinel.txt").read_text() == "app-owned\n")
    record("HTTP mode does not replace existing incoming file", (in_mt / "USERKEEP.txt").read_text() == "keep-me\n")

    auth = admin_login()
    code, status, _ = req(ADMIN, "GET", "/admin/api/transport", headers=auth)
    record("admin shows HTTP and does not probe folders", code == 200 and status.get("mode") == "HTTP" and status.get("fileEnabled") is False
           and all(not p.get("probed") for p in status.get("paths", []) if p.get("role") != "APP_MANAGED"), status.get("mode"))
    record("admin labels outgoing as simulator input", any(p.get("relative") == "outgoing/mt" and p.get("role") == "SIMULATOR_INPUT" for p in status.get("paths", [])))
    record("admin labels incoming as simulator output", any(p.get("relative") == "incoming/mx" and p.get("role") == "SIMULATOR_OUTPUT" for p in status.get("paths", [])))
    record("admin marks temp/incoming as not accessed", any(p.get("relative") == "temp/incoming" and p.get("simulatorAccess") == "NONE" for p in status.get("paths", [])))

    code, updated, _ = req(ADMIN, "POST", "/admin/api/transport", {"mode": "BOTH", "root": str(KENEX), "stableMs": 0, "pollMs": 400}, headers=auth)
    record("admin enables BOTH", code == 200 and updated.get("mode") == "BOTH" and updated.get("fileEnabled") is True, updated.get("error") or updated.get("diagnostics"))

    def mt_response():
        if not in_mt.exists():
            return None
        for p in in_mt.iterdir():
            if p.is_file() and b":21:KELC2026000001" in p.read_bytes():
                return p.name
        return None

    mt_file = wait_until("MT700 from outgoing is answered in incoming", mt_response, 25)
    if mt_file:
        record("MT scenario response landed in incoming/mt", True, mt_file)
    record("MT input left outgoing", not (out_mt / "MT700PYHNHFCY3PUDF7S.txt").exists(), names(out_mt))
    archived = KENEX / "temp" / "outgoing" / "mt" / "MT700PYHNHFCY3PUDF7S.txt"
    record("MT original moved to temp/outgoing/mt", archived.is_file(), archived)
    record("success archive is a move, not a second copy in outgoing", archived.is_file() and not (out_mt / "MT700PYHNHFCY3PUDF7S.txt").exists())
    record("no separate .swiftsim/processed success root", not (KENEX / ".swiftsim" / "processed").exists())
    record("pre-existing temp/outgoing file kept", (KENEX / "temp" / "outgoing" / "mt" / "ALREADY.txt").read_text() == "already\n")

    def mx_response():
        if not in_mx.exists():
            return None
        for p in in_mx.iterdir():
            if p.is_file() and b"tsrv.019" in p.read_bytes():
                return p.name
        return None

    mx_file = wait_until("MX from outgoing is answered in incoming", mx_response, 25)
    if mx_file:
        record("MX scenario response landed in incoming/mx", mx_file.endswith(".xml"), mx_file)
    record("MX input left outgoing", not (out_mx / "MXPYHNHE327RFJ24G.xml").exists(), names(out_mx))
    record("MX original moved to temp/outgoing/mx", (KENEX / "temp" / "outgoing" / "mx" / "MXPYHNHE327RFJ24G.xml").is_file())

    record("partial file ignored", (out_mt / "MT700PARTIAL.txt.partial").is_file())
    record("dotfile ignored", (out_mt / ".MT700HIDDEN.txt").is_file())
    record("symlink ignored", (out_mt / "MT700LINK.txt").is_symlink())
    record("zero-byte file left in place", (out_mt / "MT700ZERO.txt").is_file() and (out_mt / "MT700ZERO.txt").stat().st_size == 0)
    record("temp/incoming sentinel untouched", (temp_in / "sentinel.txt").read_text() == "app-owned\n")
    record("pre-existing incoming file untouched", (in_mt / "USERKEEP.txt").read_text() == "keep-me\n")

    def isolated(fragment):
        err = KENEX / ".swiftsim" / "error"
        if not err.exists():
            return None
        for p in err.rglob("*"):
            if p.is_file() and p.name.endswith(".reason.txt") and fragment.encode() in p.read_bytes():
                return p.read_text(errors="replace")
            if p.is_file() and not p.name.endswith(".reason.txt") and fragment.encode() in p.read_bytes() and p.with_name(p.name + ".reason.txt").exists():
                return p.with_name(p.name + ".reason.txt").read_text(errors="replace")
        return None

    bad = wait_until("malformed input isolated", lambda: isolated(b"this is not a swift message") if False else isolated("this is not a swift message"), 15)
    # isolated() searches reason and original. The original contains the phrase.
    if bad is None:
        bad = wait_until("malformed input isolated (retry)", lambda: isolated("not a swift"), 10)
    record("malformed file not left in outgoing", not (out_mt / "MTBAD.txt").exists())
    record("malformed reason kept", bad is not None and ("SIM-" in bad or "NAK" in bad), bad)

    fx = wait_until("unsupported MT300 isolated", lambda: isolated("FX300TEST"), 15)
    record("MT300 not invented; reason exposed", fx is not None and ("SIM-T01" in fx or "not supported" in fx or "NAK" in fx), fx)
    record("MT300 not left in outgoing", not (out_mt / "MT300PYHNHFCY3PUDF7S.txt").exists())
    record("failures were not moved into temp/outgoing", not any((KENEX / "temp" / "outgoing").rglob("MTBAD.txt")) and not any((KENEX / "temp" / "outgoing").rglob("MT300PYHNHFCY3PUDF7S.txt")))

    code, errs, _ = req(ADMIN, "GET", "/admin/api/transport", headers=auth)
    reasons = " ".join((e.get("reason") or "") for e in (errs.get("recentErrors") or []))
    record("admin lists isolated files", code == 200 and ("SIM-" in reasons or "FX300" in reasons or "not a swift" in reasons or errs.get("counts", {}).get("isolated", 0) >= 1), reasons[:400] or errs.get("counts"))

    def ids_for(ref):
        code, rows, _ = gw("GET", f"/api/messages?ref={ref}&limit=100&includeRaw=false")
        return sorted(r["id"] for r in rows) if code == 200 else []

    mt_ids = ids_for("KELC2026000001")
    mx_ids = ids_for("KEGTEE2026MX0001")
    incoming_before = names(in_mt) + names(in_mx)
    time.sleep(2.5)
    record("repeated poll does not replay MT", ids_for("KELC2026000001") == mt_ids, ids_for("KELC2026000001"))
    record("repeated poll does not replay MX", ids_for("KEGTEE2026MX0001") == mx_ids)
    record("repeated poll does not add incoming files", names(in_mt) + names(in_mx) == incoming_before, names(in_mt) + names(in_mx))

    # Echo of a published file must not be submitted again.
    echoed = in_mt / mt_file
    echo_name = "MT730ECHORETURN.txt"
    (out_mt / echo_name).write_bytes(echoed.read_bytes())
    def echo_archived():
        proc = KENEX / ".swiftsim" / "error"
        if not proc.exists():
            return False
        return any(p.name.startswith("MT730ECHORETURN") for p in proc.rglob("*") if p.is_file())
    wait_until("echo file archived", echo_archived, 15)
    record("echo was not submitted as a new MT730 business message", ids_for("KELC2026000001") == mt_ids, ids_for("KELC2026000001"))
    record("echo removed from outgoing", not (out_mt / echo_name).exists())

    http_sample = (SAMPLES / "mt760-issue-to-advising.fin").read_text().replace("KEGTEE2026000001", "KEHTTPFILE01")
    before_files = names(in_mt) + names(in_mx)
    code, posted, _ = gw("POST", "/api/messages", http_sample)
    record("HTTP submit still accepted in BOTH", code in (202, 422) and posted.get("status") == "ACK", (code, posted.get("status") if isinstance(posted, dict) else posted))
    time.sleep(5)
    record("HTTP-sourced reply is not copied into incoming", names(in_mt) + names(in_mx) == before_files, [n for n in names(in_mt) + names(in_mx) if n not in before_files])

    before_kick = names(in_mt)
    code, trig, _ = gw("POST", "/api/scenarios/happy-issuance/trigger?format=MT")
    record("kickoff without transport schedules", code == 200 and trig.get("scheduled"), trig)
    time.sleep(4)
    record("BOTH originate HTTP does not write kickoff into incoming", names(in_mt) == before_kick, names(in_mt))
    code, trig2, _ = gw("POST", "/api/scenarios/happy-issuance/trigger?format=MT&transport=FILE")
    record("manual kickoff transport=FILE accepted", code == 200, trig2)

    def kickoff_file():
        for p in in_mt.iterdir():
            if p.name in before_kick or not p.is_file():
                continue
            body = p.read_bytes()
            if b"{2:O760" in body or b":22A:ISSU" in body:
                return p.name
        return None

    kicked = wait_until("manual FILE kickoff appears in incoming/mt", kickoff_file, 20)
    if kicked:
        record("manual initiated MT reached payment-app incoming", True, kicked)

    # Distinct output names.
    produced = [p.name for p in in_mt.iterdir() if p.is_file() and p.name != "USERKEEP.txt"]
    record("incoming MT names are unique", len(produced) == len(set(produced)) and len(produced) >= 2, produced)
    record("generated MT names keep the txt convention", all(n.endswith(".txt") and n.startswith("MT") for n in produced), produced)

    snapshot_ids = ids_for("KELC2026000001")
    snapshot_files = (names(in_mt), names(in_mx))
    env = os.environ.copy()
    subprocess.run(["tests/temp-instance.sh", "stop"], cwd=ROOT, env=env, check=False)
    subprocess.run(["tests/temp-instance.sh", "start"], cwd=ROOT, env=env, check=False)
    deadline = time.time() + 60
    up = False
    while time.time() < deadline:
        try:
            code, _, _ = gw("GET", "/api/participants")
            if code == 200:
                up = True
                break
        except Exception:
            pass
        time.sleep(1)
    record("isolated instance restarted", up)
    time.sleep(3)
    record("restart does not replay MT", ids_for("KELC2026000001") == snapshot_ids, ids_for("KELC2026000001"))
    record("restart does not add incoming files", (names(in_mt), names(in_mx)) == snapshot_files, (names(in_mt), names(in_mx)))
    record("temp/incoming still untouched after restart", (temp_in / "sentinel.txt").read_text() == "app-owned\n")
    record("USERKEEP still intact after restart", (in_mt / "USERKEEP.txt").read_text() == "keep-me\n")

    passed = sum(1 for _, ok, _ in results if ok)
    failed = [r for r in results if not r[1]]
    print(f"\n{passed}/{len(results)} file-transport checks passed")
    if failed:
        log = DATA / "app.log"
        if log.exists():
            tail = log.read_text(errors="replace").splitlines()[-80:]
            print("--- app.log tail ---")
            print("\n".join(tail))
    return 0 if not failed else 1

if __name__ == "__main__":
    raise SystemExit(main())
