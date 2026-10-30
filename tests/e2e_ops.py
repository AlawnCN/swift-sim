# Admin console (8091 on the test instance) + AI API (8092) + MCP checks - exec'd from tests/e2e.py (shares req/check/record/sample/R helpers)
import http.cookiejar
ADMIN = os.environ.get("SIM_ADMIN_URL", "http://127.0.0.1:8091")
AI = os.environ.get("SIM_AI_URL", "http://127.0.0.1:8092")
SECRETS = os.environ.get("SIM_SECRETS_DIR", os.path.join(DATA, "secrets"))

def _creds():
    a = {}
    for l in open(os.path.join(SECRETS, "admin-console.properties")):
        if "=" in l and not l.startswith("#"): k, v = l.strip().split("=", 1); a[k] = v
    keys = {}
    for l in open(os.path.join(SECRETS, "ai-api-keys")):
        x = l.split()
        if len(x) >= 3 and not l.startswith("#"): keys[x[0]] = x[2]
    return a, keys
ADM, KEYS = _creds()
_jar = http.cookiejar.CookieJar()
_op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_jar))

def http_(base, method, path, body=None, headers=None, opener=None, raw=False):
    h = dict(headers or {})
    data = None
    if body is not None:
        if isinstance(body, (dict, list)): data = json.dumps(body).encode(); h.setdefault("Content-Type", "application/json")
        else: data = body.encode(); h.setdefault("Content-Type", "text/plain")
    r = urllib.request.Request(base + path, data=data, method=method, headers=h)
    try:
        resp = (opener or urllib.request.build_opener()).open(r, timeout=30)
        b = resp.read()
        return resp.status, (b if raw else json.loads(b or b"null"))
    except urllib.error.HTTPError as e:
        b = e.read()
        try: return e.code, json.loads(b or b"null")
        except Exception: return e.code, b
def adm(method, path, body=None, csrf=True):
    return http_(ADMIN, method, path, body, {"X-Sim-Admin": "1"} if csrf and method != "GET" else {}, _op)
def ai(method, path, body=None, key="ai-readonly", headers=None):
    h = {"Authorization": "Bearer " + KEYS[key]} if key else {}
    h.update(headers or {})
    return http_(AI, method, path, body, h)
def mcp(method, params=None, key="ai-readonly", id_=1):
    m = {"jsonrpc": "2.0", "method": method}
    if id_ is not None: m["id"] = id_
    if params is not None: m["params"] = params
    return ai("POST", "/ai/v1/mcp", m, key)
def tool(name, args, key="ai-readonly"):
    c, d = mcp("tools/call", {"name": name, "arguments": args}, key)
    try: return d["result"]["isError"], json.loads(d["result"]["content"][0]["text"])
    except Exception: return True, d

print("--- O: admin console / AI API / MCP")
# ---- port isolation
c1, _ = req("GET", "/admin/api/me"); c2, _ = req("GET", "/ai/v1/")
record("O01 gateway port does not serve /admin or /ai", c1 == 404 and c2 == 404, (c1, c2))
c1, d1 = http_(ADMIN, "GET", "/admin/api/dashboard"); c2, _ = http_(ADMIN, "GET", "/api/participants")
record("O02 admin API requires login (also /api on admin port)", c1 == 401 and c2 == 401, (c1, c2))
c, _ = http_(ADMIN, "GET", "/ai/v1/")
record("O02b admin port does not serve the AI API", c in (401, 404), c)
c, _ = ai("GET", "/admin/api/me")
record("O02c AI port does not serve the admin API", c == 404, c)
# ---- login
c, d = adm("POST", "/admin/api/login", {"username": "admin", "password": "wrong-password"})
record("O03 wrong password -> 401 (no password echoed)", c == 401 and "password" not in json.dumps(d).replace("invalid username or password", ""), (c, d))
c, d = adm("POST", "/admin/api/login", {"username": ADM["username"], "password": ADM["password"]})
record("O03 login with generated credentials", c == 200 and any(k.name == "SIMADMIN" for k in _jar), (c, d))
st = os.stat(os.path.join(SECRETS, "admin-console.properties")).st_mode & 0o777, os.stat(os.path.join(SECRETS, "ai-api-keys")).st_mode & 0o777
record("O03 secrets files are chmod 600", st == (0o600, 0o600), [oct(x) for x in st])
c, me = adm("GET", "/admin/api/me")
record("O03 /me shows file paths and key names, never secret values", c == 200 and me["user"] == ADM["username"] and ADM["password"] not in json.dumps(me)
       and all(v not in json.dumps(me) for v in KEYS.values()), {k: me.get(k) for k in ("user", "ports")})
c, dash = adm("GET", "/admin/api/dashboard")
record("O04 dashboard: sessions, ledgers, counts", c == 200 and dash["sessions"] and set(dash["ledgers"]) == {"UNDERTAKING", "LC", "COLL"} and dash["messages"]["total"] > 0,
       {"total": dash.get("messages"), "review": dash.get("reviewPending")})
c, d = adm("POST", "/admin/api/tools/backup", {}, csrf=False)
record("O05 state-changing admin call without X-Sim-Admin header -> 403", c == 403, (c, d))
# ---- messages
c, d = adm("GET", "/admin/api/messages?status=NAK&limit=20")
record("O06 message filter status=NAK", c == 200 and d["items"] and all(m["status"] == "NAK" for m in d["items"]), len(d.get("items", [])))
c, d = adm("GET", "/admin/api/messages?business=BIZREJECT&limit=20")
record("O06 message filter business rejects", c == 200 and d["items"] and all(m.get("businessStatus") in ("REJECTED", "INCONSISTENT") or m.get("businessReject") for m in d["items"]), len(d.get("items", [])))
nak_id = adm("GET", "/admin/api/messages?status=NAK&limit=1")[1]["items"][0]["id"]
c, d = adm("GET", f"/admin/api/messages/{nak_id}")
record("O06 message detail: raw, parsed fields, validation findings with codes", c == 200 and d.get("raw") and d.get("fields") and d["validation"]["findings"]
       and any(f["code"].startswith("SIM-") for f in d["validation"]["findings"]), d.get("validation", {}).get("findings", [])[:2])
# ---- ledgers
c, bl = adm("GET", "/admin/api/business")
fams = {b["family"] for b in bl} if c == 200 else set()
record("O07 business list covers all three ledgers", fams >= {"UNDERTAKING", "LC", "COLL"}, fams)
und = next(b["ref"] for b in bl if b["family"] == "UNDERTAKING" and b["status"] not in ("APPLIED",))
c, d = adm("GET", f"/admin/api/business/{und}")
record("O07 business detail with history/messages/expectedNext", c == 200 and d["history"] and d["messages"] and "expectedNext" in d, {"ref": und, "status": d.get("status")})
c, d = adm("GET", f"/admin/api/business/{und}/reconcile")
record("O07 reconcile from admin", c == 200 and "summary" in d and isinstance(d["findings"], list), d.get("summary"))
# ---- validate (dry run): nothing sent, no ISN consumed
def sess(): return {s["bic"]: (s["lastIsn"], s["lastOsn"]) for s in ai("GET", "/ai/v1/sessions")[1]}
s0, n0 = sess(), last_id()
VREF = R("VAL")
c, v = ai("POST", "/ai/v1/validate", sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": VREF}))
time.sleep(1.2)
record("O08 validate valid MT760 -> OK, nothing sent, ISN/OSN unchanged", c == 200 and v["verdict"] == "OK" and last_id() == n0 and sess() == s0
       and v["business"]["checked"] and v["business"]["status"] == "APPLIED", {"verdict": v.get("verdict"), "business": v.get("business")})
c, d = ai("GET", f"/ai/v1/business/{VREF}")
record("O08 validate left no ledger record (rolled back)", c == 404, c)
c, v = ai("POST", "/ai/v1/validate", sample("invalid-mt760-missing-fields.fin"))
record("O09 validate invalid MT760 -> WOULD_NAK with SIM- codes and fields", c == 200 and v["verdict"] == "WOULD_NAK" and any(f["code"].startswith("SIM-") for f in v["findings"]),
       [(f["code"], f.get("field")) for f in v.get("findings", [])][:4])
c, v = ai("POST", "/ai/v1/validate", sample("mt767-amendment.fin", regex={r":21:[^\n]*": ":21:NOSUCHREF0001", r":20:[^\n]*": ":20:" + R("AMX")}))
record("O10 validate MT767 for unknown undertaking -> business finding", c == 200 and v["verdict"] in ("WOULD_BUSINESS_REJECT", "BUSINESS_INCONSISTENT") and any(f["code"].startswith("BIZ-") for f in v["findings"]),
       {"verdict": v.get("verdict"), "codes": [f["code"] for f in v.get("findings", [])]})
c, v = ai("POST", "/ai/v1/validate", {"raw": ":20:" + R("FREE") + "\n:21:NONREF\n:79:VALIDATION TEST", "from": "SUT_BANK", "to": "ADVISING_BANK", "mtType": "799"})
record("O10b validate bare block 4 (JSON body) is wrapped", c == 200 and v.get("wrappedRaw", "").startswith("{1:") and v["msgType"] == "MT799" and v["verdict"] != "WOULD_NAK", {"verdict": v.get("verdict"), "codes": [f["code"] for f in v.get("findings", [])]})
# ---- send as any participant (admin) through the normal gateway
SREF = R("ADM")
c, r = adm("POST", "/admin/api/send", {"raw": sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": SREF})})
record("O11 admin send through normal gateway -> ACK + ISN", c == 202 and r["status"] == "ACK" and r["isn"], {k: r.get(k) for k in ("id", "status", "isn")})
time.sleep(3)
c, d = adm("GET", f"/admin/api/messages/{r['id']}")
record("O11 admin-sent message delivered with channel ADMIN and ledger applied", d["message"]["channel"] == "ADMIN" and d["message"]["status"] == "DELIVERED" and d["message"].get("businessStatus") == "APPLIED", d["message"])
c, d = adm("POST", "/admin/api/send", {"raw": "{1:F01GARBAGE"})
record("O11b admin send of garbage -> NAK 422", c == 422 and d["status"] == "NAK", d.get("reason"))
# ---- logs / audit
c, d = adm("GET", "/admin/api/logs?offset=-1")
off = d.get("offset", 0)
record("O12 log tail returns lines and an offset", c == 200 and d["lines"] and off > 0, len(d.get("lines", [])))
c, d2 = adm("GET", f"/admin/api/logs?offset={off}&level=ERROR")
record("O12 log incremental poll from offset", c == 200 and d2["offset"] >= off, d2.get("offset"))
c, au = adm("GET", "/admin/api/audit?limit=50")
record("O12 audit log has admin send and failed login", c == 200 and any(a["path"] == "/admin/api/send" for a in au) and any(a.get("detail") == "FAILED" for a in au), len(au))
# ---- backup / export
c, b = adm("POST", "/admin/api/tools/backup", {})
c2, bl2 = adm("GET", "/admin/api/tools/backups")
record("O13 online DB backup", c == 200 and b["bytes"] > 1000 and any(x["name"] == os.path.basename(b["file"]) for x in bl2), b)
c, ex = http_(ADMIN, "GET", f"/admin/api/tools/export?ref={SREF}", opener=_op)
record("O13 export one reference as JSON", c == 200 and ex["business"]["ref"] == SREF and "reconcile" in ex, list(ex)[:4] if isinstance(ex, dict) else c)
# ---- purge (two-phase, protected refs)
s1 = sess()
c, p = adm("POST", "/admin/api/tools/purge", {"refs": [SREF, "KEGTEE2026000101"], "dryRun": True})
record("O14 purge dry run: counts + token; protected ref skipped", c == 200 and p["undertakings"] == 1 and p["messages"] >= 2 and "KEGTEE2026000101" in p["protectedSkipped"] and p["confirmToken"].startswith("PURGE-"), p)
c, p2 = adm("POST", "/admin/api/tools/purge", {"refs": [SREF], "dryRun": False, "confirm": "PURGE-0000x"})
record("O14 purge with wrong token refused", c == 400, (c, p2))
c, p3 = adm("POST", "/admin/api/tools/purge", {"refs": [SREF, "KEGTEE2026000101"], "dryRun": False, "confirm": p["confirmToken"]})
c4, _ = ai("GET", f"/ai/v1/business/{SREF}")
c5, m5 = adm("GET", f"/admin/api/messages?ref={SREF}&includeSys=true")
record("O14 purge executed: record + messages gone, ISN/OSN untouched", c == 200 and c4 == 404 and not m5["items"] and sess() == s1, p3)
c, p = adm("POST", "/admin/api/tools/purge", {"dryRun": True})
record("O14 purge needs an explicit selection", c == 400, p)
# ---- review preview (admin) + respond (AI operator)
req("POST", "/api/participants/ADVISING_BANK/review-mode", {"enabled": True})
QREF = R("RVW")
post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": QREF}))
it = rq(QREF, wait=8)
n1 = last_id()
c, pv = adm("POST", f"/admin/api/review-queue/{it['id'] if it else 0}/preview", {"action": "ACK"})
time.sleep(1)
c2, it2 = ai("GET", f"/ai/v1/review-queue/{it['id'] if it else 0}")
record("O15 review preview renders draft, sends nothing, item stays pending", c == 200 and pv["preview"] and pv["drafts"] and pv["drafts"][0]["raw"].startswith("{1:")
       and last_id() == n1 and it2["item"]["status"] == "PENDING_REVIEW", {"drafts": [d.get("template") for d in pv.get("drafts", [])]})
c, v = ai("POST", "/ai/v1/validate", pv["drafts"][0]["raw"] if c == 200 else "")
record("O15 preview draft validates OK", c == 200 and v["verdict"] == "OK", v.get("verdict"))
c, d = ai("POST", f"/ai/v1/review-queue/{it['id'] if it else 0}/respond", {"action": "ACK"})
record("O16 read-only key cannot respond (403 AI-SCOPE)", c == 403 and d["code"] == "AI-SCOPE", d)
c, d = ai("POST", f"/ai/v1/review-queue/{it['id'] if it else 0}/preview", {"action": "ACK"})
record("O16 read-only key may preview", c == 200 and d["preview"], c)
c, d = ai("POST", f"/ai/v1/review-queue/{it['id'] if it else 0}/respond", {"action": "ACK", "note": "e2e operator"}, key="ai-operator")
record("O16 operator key responds -> RESPONDED with message", c == 200 and d["item"]["status"] == "RESPONDED" and d["sent"] and d["sent"][0].get("messageId"), d.get("sent"))
req("POST", "/api/participants/ADVISING_BANK/review-mode", {"enabled": False})
# ---- AI auth / scopes
c1, _ = ai("GET", "/ai/v1/messages", key=None)
c2, _ = http_(AI, "GET", "/ai/v1/messages", headers={"Authorization": "Bearer sk-not-a-real-key-0000000000"})
c3, d3 = ai("GET", "/ai/v1/whoami")
c4, d4 = ai("GET", "/ai/v1/whoami", key="ai-operator")
c5, _ = ai("GET", "/ai/v1/whoami", key=None, headers={"X-Api-Key": KEYS["ai-readonly"]})
record("O17 AI auth: none/bad -> 401; keys map to read/operator; X-Api-Key accepted", (c1, c2, c3, c4, c5) == (401, 401, 200, 200, 200) and d3["scope"] == "read" and d4["scope"] == "operator", (c1, c2, d3, d4, c5))
c, d = ai("POST", "/ai/v1/scenarios/guarantee-issue-inbound/trigger", {})
record("O17 read-only key cannot trigger scenarios", c == 403, c)
c, au = adm("GET", "/admin/api/audit?limit=50&q=DENIED")
record("O17 denied AI attempts are audit-logged", any(a["actor"] == "ai:ai-readonly" for a in au), len(au))
c, d = ai("GET", "/ai/v1/openapi.json", key=None)
record("O17 OpenAPI document (no auth)", c == 200 and d["openapi"].startswith("3.") and "/ai/v1/business/{ref}/reconcile" in d["paths"], len(d.get("paths", {})) if isinstance(d, dict) else c)
# ---- cursor polling
c, d = ai("GET", "/ai/v1/messages?limit=5")
cur = d["items"][-1]["id"] - 1 if d["items"] else 0
c, d = ai("GET", f"/ai/v1/messages?sinceId={cur}&limit=3&includeSys=true")
ids = [m["id"] for m in d["items"]]
record("O18 cursor polling: ascending ids > sinceId, cursor = last id", c == 200 and ids and ids == sorted(ids) and ids[0] > cur and d["cursor"] == ids[-1], {"ids": ids, "cursor": d.get("cursor"), "hasMore": d.get("hasMore")})
c, d2 = ai("GET", f"/ai/v1/messages?sinceId={last_id()}&includeSys=true")
record("O18 polling at the head returns nothing and keeps the cursor", c == 200 and d2["items"] == [] and d2["cursor"] == last_id() and d2["hasMore"] is False, d2.get("cursor"))
# ---- AI business / reconcile / reference
c, d = ai("GET", f"/ai/v1/business/{QREF}/reconcile")
record("O19 AI reconcile: clean flow (MT760 + MT768) has no ERROR/WARN", c == 200 and d["family"] == "UNDERTAKING" and d["summary"]["ok"] and d["summary"]["counts"]["WARN"] == 0 and len(d["timeline"]) >= 2, [f["code"] for f in d.get("findings", [])])
c, d = ai("GET", "/ai/v1/business/NO-SUCH-REF/reconcile")
record("O19 reconcile unknown ref -> 404", c == 404, c)
rej = [m for m in ai("GET", "/ai/v1/messages?business=BIZREJECT&limit=50")[1]["items"] if m.get("businessRef") and m.get("businessStatus") == "REJECTED"]
if rej:
    c, d = ai("GET", f"/ai/v1/business/{rej[0]['businessRef']}/reconcile")
    record("O19 reconcile flags a rejected message as ERROR with evidence", c == 200 and any(f["severity"] == "ERROR" and rej[0]["id"] in f.get("evidence", []) for f in d["findings"]), [f["code"] for f in d.get("findings", [])][:6])
c, d = ai("GET", "/ai/v1/reference/rule-codes")
codes = {x["code"] for x in d["codes"]}
record("O20 rule-code catalogue", {"BIZ-U01", "SIM-M01", "BIZ-L06"} <= codes, len(codes))
c, d = ai("GET", "/ai/v1/reference/state-machines")
record("O20 state machines per product", set(d["products"]) == {"UNDERTAKING", "LC", "COLL"}, list(d["products"]))
c, d = ai("GET", "/ai/v1/messages/" + str(nak_id))
record("O20 AI message analysis", c == 200 and d["validation"]["findings"] and d["raw"], d["message"]["msgType"])
# ---- MCP
c, d = mcp("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "e2e", "version": "1"}})
record("O21 MCP initialize", c == 200 and d["result"]["serverInfo"]["name"] == "swift-sim" and "tools" in d["result"]["capabilities"], d.get("result", {}).get("protocolVersion"))
c, d = mcp("notifications/initialized", id_=None)
record("O21 MCP notification -> 202", c == 202, c)
c, d = mcp("tools/list")
names = {t["name"] for t in d["result"]["tools"]}
record("O21 MCP tools/list", {"list_messages", "get_message", "get_business", "reconcile_business", "validate_message", "respond_review", "trigger_scenario"} <= names, sorted(names))
err, r = tool("reconcile_business", {"ref": QREF})
record("O21 MCP tools/call reconcile_business", not err and r["ref"] == QREF and "findings" in r, r.get("summary"))
err, r = tool("validate_message", {"raw": sample("invalid-mt760-missing-fields.fin")})
record("O21 MCP validate_message", not err and r["verdict"] == "WOULD_NAK", r.get("verdict"))
err, r = tool("list_messages", {"sinceId": cur, "limit": 2})
record("O21 MCP list_messages with cursor", not err and len(r["items"]) <= 2 and r["cursor"] >= cur, r.get("cursor"))
err, r = tool("respond_review", {"id": 1, "action": "ACK"})
record("O21 MCP operator tool refused for read key", err and "operator" in r.get("error", ""), r)
err, r = tool("get_reference", {"kind": "sessions"})
record("O21 MCP get_reference sessions", not err and isinstance(r, list) and r, len(r) if isinstance(r, list) else r)
c, d = mcp("no/such")
record("O21 MCP unknown method -> JSON-RPC error", c == 200 and d["error"]["code"] == -32601, d.get("error"))
c, d = http_(AI, "POST", "/ai/v1/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
record("O21 MCP requires the Bearer key", c == 401, c)
