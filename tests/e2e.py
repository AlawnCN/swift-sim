#!/usr/bin/env python3
"""End-to-end test of a running simulator (default: the throw-away test instance http://127.0.0.1:8090).
Usage: python3 tests/e2e.py [--no-restart] [--only-new]
Run it against a throw-away instance (see tests/temp-instance.sh), never against the live data:
  tests/temp-instance.sh start
  SIM_URL=http://127.0.0.1:8090 SIM_DATA_DIR=/tmp/swift-sim-e2e SIM_STOP_CMD="tests/temp-instance.sh stop" \
  SIM_START_CMD="tests/temp-instance.sh start" python3 tests/e2e.py
Every run uses fresh undertaking numbers (suffix derived from the clock), so the ledger does not need to be wiped.
The restart phase stops/starts the simulator with ./stop.sh and ./run.sh."""
import json, os, re, shutil, subprocess, sys, time, urllib.request, urllib.parse, datetime

BASE = os.environ.get("SIM_URL", "http://127.0.0.1:8090")   # default = throw-away test instance, never the live 8080
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLES = os.path.join(ROOT, "samples")
DATA = os.environ.get("SIM_DATA_DIR", "/tmp/swift-sim-e2e")
STOP_CMD = os.environ.get("SIM_STOP_CMD", os.path.join(ROOT, "tests", "temp-instance.sh") + " stop")
START_CMD = os.environ.get("SIM_START_CMD", os.path.join(ROOT, "tests", "temp-instance.sh") + " start")
SUT, ISS, ADV, BEN, APP = "TSTAKEN0XXX", "TSTBGB20XXX", "TSTCKEN0XXX", "TSTDKEN0XXX", "TSTECN20XXX"
MT759, MX19, MX08 = "MT759", "tsrv.019.001.01", "tsrv.008.001.01"
results = []
RUN = (lambda n: "".join("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"[(n // 36 ** i) % 36] for i in range(5))[::-1])(int(time.time()) % 36 ** 5)
def R(tag): return f"{tag}{RUN}"          # <= 16 chars
TODAY = datetime.date.today()

# ------------------------------------------------------------------ helpers
def req(method, path, body=None, ctype="text/plain"):
    if isinstance(body, (dict, list)): body, ctype = json.dumps(body), "application/json"
    data = body.encode() if isinstance(body, str) else body
    r = urllib.request.Request(BASE + path, data=data, method=method, headers={"Content-Type": ctype} if data is not None else {})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read() or b"null")
        except Exception: return e.code, None

def last_id():
    _, l = req("GET", "/api/messages?limit=1&includeRaw=false")
    return l[0]["id"] if l else 0

def msgs_since(since):
    _, l = req("GET", f"/api/messages?sinceId={since}&limit=500")
    return list(reversed(l))

def sample(name, repl=None, regex=None):
    with open(os.path.join(SAMPLES, name)) as f: s = f.read()
    for a, b in (repl or {}).items():
        assert a in s, f"{a} not in {name}"
        s = s.replace(a, b)
    for a, b in (regex or {}).items(): s = re.sub(a, b, s, flags=re.S)
    return s

def post(raw, **params):
    q = urllib.parse.urlencode(params)
    return req("POST", "/api/messages" + ("?" + q if q else ""), raw)

def activate(s): req("POST", f"/api/scenarios/{s}/activate")

def trig(s, fmt="MT", body=None, **params):
    q = urllib.parse.urlencode(dict(format=fmt, **params))
    return lambda: req("POST", f"/api/scenarios/{s}/trigger?{q}", body or {})

def record(name, ok, detail):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + " :: " + str(detail)[:300])
    return ok

def check(name, action, expect, wait=6.0, forbid=(), expect_nak=False, nak_code=None, holder=None):
    """expect: list of (msgType, substring-in-raw or None) that must appear as OUT/INTERNAL messages after the action.
    forbid: (msgType or (msgType, substring)) that must not appear during the whole window."""
    since = last_id()
    resp = action()
    if holder is not None: holder.append(resp)
    deadline = time.time() + wait
    ok, detail = False, ""
    while time.time() < deadline:
        time.sleep(0.5)
        ms = msgs_since(since)
        outs = [m for m in ms if m["direction"] in ("OUT", "INTERNAL")]
        missing = [f"{t}{'~' + sub if sub else ''}" for t, sub in expect
                   if not any(m["msgType"] == t and (sub is None or sub in (m["raw"] or "")) for m in outs)]
        naks = [m for m in outs if m["status"] == "NAK"]
        def forbidden(m):
            for fb in forbid:
                t, sub = fb if isinstance(fb, tuple) else (fb, None)
                if m["msgType"] == t and (sub is None or sub in (m["raw"] or "")): return True
            return False
        forb = [m["msgType"] for m in outs if forbidden(m)]
        if expect_nak:
            body = resp[1] or {}
            errs = body.get("errors", [])
            ok = body.get("status") == "NAK" and any(m["msgType"] == "NAK" for m in ms) and \
                 (nak_code is None or any(nak_code in e for e in errs)) and ("{451:1}" in (body.get("ack") or "") or (body.get("ack") or "").lstrip().startswith("<"))
            detail = "; ".join(errs[:3])
            if ok: break
            continue
        if forbid and time.time() < deadline - 0.6: continue
        if not missing and not naks and not forb:
            ok = True
            detail = ", ".join(f"{m['msgType']}({m['senderBic']}->{m['receiverBic']},{m['status']})" for m in outs) or "no outbound message (as expected)"
            break
        detail = f"missing={missing} naks={[(m['msgType'], m['statusReason']) for m in naks]} forbidden={forb}"
    return record(name, ok, detail)

def ledger(ref):
    c, d = req("GET", f"/api/undertakings/{ref}")
    return d if c == 200 else None

def lcheck(name, ref, cond, wait=6.0):
    """poll the ledger until cond(detail) is truthy"""
    deadline, d = time.time() + wait, None
    while time.time() < deadline:
        d = ledger(ref)
        try:
            if d and cond(d):
                return record(name, True, f"{ref} status={d['status']} outstanding={d['outstanding']} expiry={d['expiryDate']}")
        except Exception: pass
        time.sleep(0.5)
    return record(name, False, f"{ref}: " + (json.dumps({k: d.get(k) for k in ('status', 'outstanding', 'amount', 'expiryDate', 'parties')}, default=str)[:250] if d else "not found"))

def events(d): return [e["eventType"] for e in d["history"]]
def pending_demand(ref):
    d = ledger(ref)
    return next((x["demandRef"] for x in reversed(d["demands"]) if x["status"] == "RECEIVED"), None) if d else None
def is_up():
    try: return req("GET", "/api/participants")[0] == 200
    except Exception: return False

def bare(mt, frm, to, block4, **extra):
    return lambda: post(block4, **{"from": frm, "to": to, "mtType": mt, **extra})

def amend_mt(ref, seq, extra=None):
    return sample("mt767-amendment.fin", {":20:KEGTEE2026000001": f":20:{ref}", ":26E:1": f":26E:{seq}", **(extra or {})})

def tsrv005(ref, seq, biz, increase=True, new_expiry="2028-01-31"):
    s = sample("tsrv.005-amendment.xml", {"KEGTEE2026MX0001": ref, "<SeqNb>1": f"<SeqNb>{seq}", "KEMX0002": biz, "2028-01-31": new_expiry})
    if not increase: s = re.sub(r"<UdrtkgAmtAdjstmnt>.*?</UdrtkgAmtAdjstmnt>", "", s, flags=re.S)
    return s

if not is_up(): print("simulator not reachable at " + BASE); sys.exit(2)
print(f"run suffix {RUN}")
req("DELETE", "/api/admin/business-date")
for p in ["SUT_BANK", "ISSUING_BANK", "ADVISING_BANK", "BENEFICIARY_BANK", "APPLICANT_BANK"]:
    req("POST", f"/api/participants/{p}/review-mode", {"enabled": False})
for s in ["happy-issuance", "amendment-accepted", "demand-paid", "extend-or-pay", "non-extension", "release-termination"]: activate(s)

G1, S1, G2, G3, G7, G8, X1, X2, X7, AP1, R1, R2, R3, S2 = (R(t) for t in
    ["KG1", "KS1", "KG2", "KG3", "GB7", "GB8", "KX1", "KX2", "GX7", "AP1", "RV1", "RS2", "RS3", "KS2"])
MT760_G1 = sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": G1})

# ================================================================ A. SUT is the ISSUER (MT)
print("--- A. SUT as issuing bank (MT 7xx)")
check("A1 MT760 -> MT768 (advised)", lambda: post(MT760_G1), [("MT768", f":21:{G1}")])
lcheck("A1 ledger ADVISED, parties ISSUER/ADVISING/BENEFICIARY", G1, lambda d: d["status"] == "ADVISED" and d["outstanding"] == 50000
       and {p["role"] for p in d["parties"]} >= {"ISSUER", "ADVISING"} and events(d)[:2] == ["ISSUED", "ADVISED"])
check("A2 SBLC MT760 -> MT768", lambda: post(sample("mt760-standby-to-advising.fin", {"KESBLC2026000001": S1})), [("MT768", f":21:{S1}")])
lcheck("A2 SBLC ledger: beneficiary bank is party", S1, lambda d: any(p["role"] == "BENEFICIARY" and p["bic"] == BEN for p in d["parties"]))
check("A3 duplicate undertaking number -> MT759 BIZ-U02", lambda: post(MT760_G1.replace("SAMPLE760001", "DUP760" + RUN)), [(MT759, "BIZ-U02")], forbid=("MT768",), wait=4)
check("A4 amendment for unknown undertaking -> BIZ-U01", lambda: post(amend_mt(R("KXX"), 1)), [(MT759, "BIZ-U01")])
check("A5 MT767 amendment 1 -> MT768 + MT787 ACPT", lambda: post(amend_mt(G1, 1)), [("MT768", None), ("MT787", ":23R:ACPT")])
lcheck("A5 ledger AMENDED amount 60000, expiry 2028-01-31", G1, lambda d: d["status"] == "AMENDED" and d["amount"] == 60000
       and d["outstanding"] == 60000 and d["expiryDate"] == "2028-01-31" and d["amendments"][0]["status"] == "ACCEPTED")
check("A6 amendment sequence mismatch (26E:1 again) -> BIZ-A02", lambda: post(amend_mt(G1, 1)), [(MT759, "BIZ-A02")])
check("A7 amendment 2 with no beneficiary answer -> only MT768", lambda: post(amend_mt(G1, 2), scenario="amendment-no-response"), [("MT768", None)], forbid=("MT787",), wait=4)
lcheck("A7 ledger AMENDMENT_PENDING", G1, lambda d: d["status"] == "AMENDMENT_PENDING" and d["amendments"][1]["status"] == "PENDING")
check("A8 second amendment while one pending -> BIZ-A01", lambda: post(amend_mt(G1, 3)), [(MT759, "BIZ-A01")])
check("A9 role violation: issuer sends MT765 demand -> BIZ-R03", bare("765", "SUT_BANK", "ADVISING_BANK",
      f":20:{G1}\n:21:{R('DMX')}\n:52A:{SUT}\n:22G:PAYM\n:32B:USD1000,00"), [(MT759, "BIZ-R03")])
check("A10 non-party receiver: MT768 to applicant bank -> BIZ-R02", bare("768", "SUT_BANK", "APPLICANT_BANK",
      f":20:{R('ACK')}\n:21:{G1}\n:30:261029"), [(MT759, "BIZ-R02")])
check("A11 MT785 non-extension -> MT768", lambda: post(sample("mt785-non-extension.fin", {"KEGTEE2026000001": G1})), [("MT768", None)])
lcheck("A11 ledger nonExtensionNotified", G1, lambda d: d["nonExtensionNotified"] is True)
# demand to SUT (beneficiary bank presents on the SBLC) and refusal by SUT
check("A12 kickoff demand from beneficiary bank -> MT765 to SUT", trig("demand-paid", ref=S1), [("MT765", f":20:{S1}")])
lcheck("A12 ledger DEMAND_RECEIVED", S1, lambda d: d["status"] == "DEMAND_RECEIVED")
dref = pending_demand(S1) or "NONE"
check("A13 SUT refuses with MT786 -> MT768", lambda: post(sample("mt786-demand-refusal.fin", {"KEGTEE2026000001": S1, "BENDMD0001": dref})), [("MT768", None)])
lcheck("A13 ledger REFUSED", S1, lambda d: d["status"] == "REFUSED" and d["demands"][-1]["status"] == "REFUSED")
check("A14 MT769 reduction 20000 -> MT768", bare("769", "SUT_BANK", "ADVISING_BANK", f":20:{R('RED')}\n:21:{S1}\n:30:261029\n:32B:USD20000,00"), [("MT768", None)])
lcheck("A14 ledger REDUCED outstanding 100000", S1, lambda d: d["status"] == "REDUCED" and d["outstanding"] == 100000)
check("A15 reduction above outstanding -> BIZ-M01", bare("769", "SUT_BANK", "ADVISING_BANK", f":20:{R('RED')}\n:21:{S1}\n:30:261029\n:32B:USD999999,00"), [(MT759, "BIZ-M01")])
check("A16 currency mismatch on reduction -> BIZ-C01", bare("769", "SUT_BANK", "ADVISING_BANK", f":20:{R('RED')}\n:21:{S1}\n:30:261029\n:32B:EUR100,00"), [(MT759, "BIZ-C01")])
check("A17 MT769 release in full -> MT768", lambda: post(sample("mt769-release.fin", {"KEGTEE2026000001": G1, "USD50000,00": "USD60000,00"})), [("MT768", None)])
lcheck("A17 ledger RELEASED outstanding 0", G1, lambda d: d["status"] == "RELEASED" and d["outstanding"] == 0)
check("A18 message after release -> BIZ-S03", lambda: post(sample("mt785-non-extension.fin", {"KEGTEE2026000001": G1})), [(MT759, "BIZ-S03")])
check("A19 MT759 to applicant bank (non-party, free format) -> MT759 reply", lambda: post(sample("mt759-to-applicant-bank.fin", {"KEGTEE2026000001": S1})), [(MT759, "GENINFAD")])

# ================================================================ B. SUT is ADVISING bank (ISSUING_BANK issues)
print("--- B. SUT as advising bank (MT)")
check("B1 kickoff: issuing bank sends MT760 to SUT", trig("happy-issuance", ref=G7), [("MT760", f":20:{G7}")])
lcheck("B1 ledger ISSUED, SUT = ADVISING", G7, lambda d: d["status"] == "ISSUED" and any(p["role"] == "ADVISING" and p["bic"] == SUT for p in d["parties"]))
check("B2 SUT MT768 -> issuer acknowledges", lambda: post(sample("mt768-ack-from-sut.fin", {"GBGTEE2026000777": G7})), [("MT768", None)])
lcheck("B2 ledger ADVISED", G7, lambda d: d["status"] == "ADVISED")
paym = lambda dr, amt="USD25000,00", **kw: post(sample("mt765-demand-paym.fin", {"GBGTEE2026000777": G7, "KEDMD2026000001": dr, "USD25000,00": amt}), **kw)
check("B3 demand paid: MT765 -> MT768 + MT202 + MT769", lambda: paym(R("DM1")), [("MT768", None), ("MT202", f":21:{G7}"), ("MT769", None)], wait=10)
lcheck("B3 ledger outstanding 25000, demand PAID", G7, lambda d: d["outstanding"] == 25000 and d["demands"][0]["status"] == "PAID" and d["status"] in ("PAID", "REDUCED"))
check("B4 demand above outstanding -> BIZ-D01", lambda: paym(R("DM2"), "USD30000,00"), [(MT759, "BIZ-D01")])
check("B5 demand in wrong currency -> BIZ-C01", lambda: paym(R("DM3"), "EUR1000,00"), [(MT759, "BIZ-C01")])
activate("demand-refused")
check("B6 demand refused: MT765 -> MT786", lambda: paym(R("DM4"), "USD10000,00"), [("MT786", None)], wait=8)
activate("demand-paid")
lcheck("B6 ledger REFUSED, outstanding unchanged", G7, lambda d: d["status"] == "REFUSED" and d["outstanding"] == 25000)
check("B7 duplicate demand reference -> BIZ-D02", lambda: paym(R("DM1"), "USD1000,00"), [(MT759, "BIZ-D02")])
check("B8 demand under examination (no answer)", lambda: paym(R("DM5"), "USD5000,00", scenario="demand-pending"), [("MT768", None)], forbid=("MT202", "MT786"), wait=4)
lcheck("B8 ledger DEMAND_RECEIVED", G7, lambda d: d["status"] == "DEMAND_RECEIVED")
check("B9 second demand while first pending -> BIZ-D03", lambda: paym(R("DM6"), "USD1000,00"), [(MT759, "BIZ-D03")])
check("B10 tsrv.017 withdrawal of pending demand -> tsrv.019", lambda: post(sample("tsrv.017-demand-withdrawal.xml",
      {"GBGTEE2026000777": G7, "KEDMDMXPAYM": R("DM5"), "KEMX0008": R("W1")})), [(MX19, None)])
lcheck("B10 ledger demand WITHDRAWN", G7, lambda d: any(x["demandRef"] == R("DM5") and x["status"] == "WITHDRAWN" for x in d["demands"]) and d["status"] != "DEMAND_RECEIVED")
check("B11 extend-or-pay MT765 PAEX -> MT768 + MT767 extension", lambda: post(sample("mt765-demand-paex.fin",
      {"GBGTEE2026000777": G7, "KEDMD2026000002": R("DM7")})), [("MT768", None), ("MT767", ":31E:271231")], wait=10)
lcheck("B11 ledger EXTENDED to 2027-12-31, demand EXTENDED", G7, lambda d: d["status"] == "EXTENDED" and d["expiryDate"] == "2027-12-31"
       and d["demands"][-1]["status"] == "EXTENDED")
check("B12 MT787 without pending amendment -> BIZ-A03", lambda: post(sample("mt787-amendment-response.fin", {"GBGTEE2026000777": G7})), [(MT759, "BIZ-A03")])
check("B13 MT759 query to issuer -> MT759 GENINFAD", lambda: post(sample("mt759-to-applicant-bank.fin", {"KEGTEE2026000001": G7, "I759TSTECN20XXXX": "I759TSTBGB20XXXX"})), [(MT759, "GENINFAD")])
check("B14 tsrv.010 non-extension request (MX on MT undertaking) -> tsrv.019", lambda: post(sample("tsrv.010-non-extension-request.xml",
      {"GBGTEE2026000777": G7, "KEMX0006": R("NX")})), [(MX19, None)])
check("B15 kickoff non-extension -> MT785 with ledger expiry", trig("non-extension", ref=G7), [("MT785", ":31E:271231")])
check("B16 kickoff release (undertaking suggested from ledger) -> MT769 for full outstanding", trig("release-termination"), [("MT769", f":21:{G7}"), ("MT769", "USD25000,00")])
lcheck("B16 ledger RELEASED", G7, lambda d: d["status"] == "RELEASED" and d["outstanding"] == 0)
check("B17 demand after release -> BIZ-S03", lambda: paym(R("DM8"), "USD1000,00"), [(MT759, "BIZ-S03")])
check("B18 kickoff issuance G8 -> MT760", trig("happy-issuance", ref=G8), [("MT760", f":20:{G8}")])
lcheck("B18 ledger G8 ISSUED", G8, lambda d: d["status"] == "ISSUED")
c, bd = req("POST", "/api/admin/business-date", {"date": str(TODAY + datetime.timedelta(days=400))})
record("B19 move business date +400 days + expiry sweep", c == 200 and req("POST", "/api/admin/expiry-sweep")[0] == 200, bd)
lcheck("B19 ledger EXPIRED", G8, lambda d: d["status"] == "EXPIRED")
check("B20 demand after expiry -> BIZ-S02", lambda: post(sample("mt765-demand-paym.fin", {"GBGTEE2026000777": G8, "KEDMD2026000001": R("DM9")})), [(MT759, "BIZ-S02")])
req("DELETE", "/api/admin/business-date")

# ================================================================ C. MX (ISO 20022 tsrv / tsin)
print("--- C. ISO 20022 MX")
check("C1 tsrv.001 -> tsrv.019", lambda: post(sample("tsrv.001-issuance.xml", {"KEGTEE2026MX0001": X1, "KEMX0001": R("M1")})), [(MX19, X1)])
lcheck("C1 ledger ADVISED", X1, lambda d: d["status"] == "ADVISED" and d["format"] == "MX")
check("C2 tsrv.005 amendment 1 -> tsrv.008 ACCP", lambda: post(tsrv005(X1, 1, R("M2"))), [(MX08, "AmdmntSts>ACCP")])
lcheck("C2 ledger AMENDED 60000 / 2028-01-31", X1, lambda d: d["status"] == "AMENDED" and d["amount"] == 60000 and d["expiryDate"] == "2028-01-31")
activate("amendment-rejected")
check("C3 tsrv.005 amendment 2 -> tsrv.008 REJT", lambda: post(tsrv005(X1, 2, R("M3"))), [(MX08, "AmdmntSts>REJT")])
activate("amendment-accepted")
lcheck("C3 ledger amendment 2 REJECTED, amount unchanged", X1, lambda d: d["amendments"][1]["status"] == "REJECTED" and d["amount"] == 60000)
check("C4 tsrv.011 non-extension -> tsrv.019", lambda: post(sample("tsrv.011-non-extension-notification.xml", {"KEGTEE2026MX0001": X1, "KEMX0005": R("M4")})), [(MX19, None)])
check("C5 kickoff extend-or-pay tsrv.013 PAEX to SUT", trig("extend-or-pay", "MX", {"demandId": R("XD1")}, ref=X1, **{"from": "ADVISING_BANK"}),
      [("tsrv.013.001.01", X1)])
lcheck("C5 ledger EXTEND_OR_PAY_PENDING", X1, lambda d: d["status"] == "EXTEND_OR_PAY_PENDING")
c, _ = req("POST", f"/api/undertakings/{X1}/parties", {"participant": "APPLICANT_BANK", "role": "APPLICANT"})
record("C6 register applicant bank as APPLICANT party", c == 200, c)
check("C6 tsrv.014 to applicant bank -> tsrv.015 EXTD", lambda: post(sample("tsrv.014-extend-or-pay-request.xml",
      {"KEGTEE2026MX0001": X1, "BENDMD0002": R("XD1"), "KEMX0004": R("M5"), "2027-12-31": "2029-01-31"})), [("tsrv.015.001.01", X1)], wait=8)
lcheck("C6 ledger EOP forwarded + applicant answer", X1, lambda d: "EXTEND_OR_PAY_FORWARDED" in events(d) and "EXTEND_OR_PAY_RESPONSE" in events(d))
check("C7 SUT extends via tsrv.005 (no consent needed) -> no tsrv.008", lambda: post(tsrv005(X1, 3, R("M6"), increase=False, new_expiry="2029-01-31")),
      [], forbid=(MX08,), wait=4)
lcheck("C7 ledger EXTENDED to 2029-01-31", X1, lambda d: d["status"] == "EXTENDED" and d["expiryDate"] == "2029-01-31")
check("C8 kickoff demand tsrv.013 PAYM to SUT", trig("demand-refused", "MX", {"demandId": R("XD2")}, ref=X1, **{"from": "ADVISING_BANK"}), [("tsrv.013.001.01", X1)])
lcheck("C8 ledger DEMAND_RECEIVED", X1, lambda d: d["status"] == "DEMAND_RECEIVED")
check("C9 SUT refuses with tsrv.016 -> tsrv.019", lambda: post(sample("tsrv.016-demand-refusal.xml",
      {"KEGTEE2026MX0001": X1, "BENDMD0003": R("XD2"), "KEMX0010": R("M7"), f"<BICFI>{BEN}": f"<BICFI>{ADV}"})), [(MX19, None)])
lcheck("C9 ledger REFUSED", X1, lambda d: d["status"] == "REFUSED")
activate("demand-paid")   # (a kickoff trigger activates its scenario)
check("C10 role violation tsrv.015 from issuer -> tsrv.019 REJT BIZ-R03", lambda: post(sample("tsrv.015-extend-or-pay-response.xml",
      {"GBGTEE2026000777": X1, "KEDMDMXPAEX": R("XD1"), "KEMX0011": R("M8")})), [(MX19, "Sts>REJT"), (MX19, "BIZ-R03")])
check("C11 tsrv.012 termination -> tsrv.019", lambda: post(sample("tsrv.012-termination.xml", {"KEGTEE2026MX0001": X1, "KEMX0007": R("M9")})), [(MX19, None)])
lcheck("C11 ledger TERMINATED", X1, lambda d: d["status"] == "TERMINATED")
check("C12 amendment after termination -> REJT BIZ-S03", lambda: post(tsrv005(X1, 4, R("MA"))), [(MX19, "BIZ-S03")])
check("C13 kickoff MX issuance by issuing bank -> tsrv.001", trig("happy-issuance", "MX", ref=X7), [("tsrv.001.001.01", X7)])
check("C14 SUT tsrv.019 ISSU/ACCP -> issuer status reply", lambda: post(sample("tsrv.019-status-report.xml", {"GBGTEE2026000777": X7, "KEMX0012": R("MB")})), [(MX19, None)])
lcheck("C14 ledger ADVISED", X7, lambda d: d["status"] == "ADVISED")
mxdem = lambda f, dr, amt="25000.00", **kw: post(sample(f, {"GBGTEE2026000777": X7, "KEDMDMXPAYM" if "paym" in f else "KEDMDMXPAEX": dr,
      "KEMX0003PAYM" if "paym" in f else "KEMX0003PAEX": R("D") + dr[-4:], "25000.00": amt}), **kw)
check("C15 tsrv.013 PAYM -> tsrv.019 DMND (paid)", lambda: mxdem("tsrv.013-demand-paym.xml", R("XD3")), [(MX19, "DMND")])
lcheck("C15 ledger PAID outstanding 25000", X7, lambda d: d["status"] == "PAID" and d["outstanding"] == 25000)
check("C16 tsrv.013 PAYM pending (no answer)", lambda: mxdem("tsrv.013-demand-paym.xml", R("XD4"), "5000.00", scenario="demand-pending"), [], forbid=(MX19,), wait=3)
lcheck("C16 ledger DEMAND_RECEIVED", X7, lambda d: d["status"] == "DEMAND_RECEIVED")
check("C17 tsrv.017 withdrawal -> tsrv.019", lambda: post(sample("tsrv.017-demand-withdrawal.xml", {"GBGTEE2026000777": X7, "KEDMDMXPAYM": R("XD4"), "KEMX0008": R("MC")})), [(MX19, None)])
lcheck("C17 ledger demand WITHDRAWN", X7, lambda d: d["demands"][-1]["status"] == "WITHDRAWN" and d["status"] == "PAID")
check("C18 tsrv.013 PAEX -> tsrv.019 + tsrv.005 extension", lambda: mxdem("tsrv.013-demand-paex.xml", R("XD5")), [(MX19, None), ("tsrv.005.001.01", "2027-12-31")], wait=10)
lcheck("C18 ledger EXTENDED 2027-12-31", X7, lambda d: d["status"] == "EXTENDED" and d["expiryDate"] == "2027-12-31")
check("C19 tsrv.008 without pending amendment -> REJT BIZ-A03", lambda: post(sample("tsrv.008-amendment-response.xml", {"GBGTEE2026000777": X7, "KEMX0009": R("MD")})), [(MX19, "BIZ-A03")])
check("C20 tsrv.010 -> tsrv.019", lambda: post(sample("tsrv.010-non-extension-request.xml", {"GBGTEE2026000777": X7, "KEMX0006": R("ME")})), [(MX19, None)])
check("C21 tsin.005 application -> tsrv.019 + tsrv.001", lambda: post(sample("tsin.005-application.xml", {"KEAPPL2026000001": AP1, "KEMX0013": R("MF")})),
      [(MX19, AP1), ("tsrv.001.001.01", AP1)], wait=8)
lcheck("C21 ledger ISSUED, SUT = APPLICANT", AP1, lambda d: d["status"] == "ISSUED" and any(p["role"] == "APPLICANT" and p["bic"] == SUT for p in d["parties"]))
activate("issuance-duplicate")
check("C22 issuance-duplicate MT (2x MT768, PDE)", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": G2})), [("MT768", None), ("MT768", "{PDE:}")])
lcheck("C22 PDE copy ignored by ledger", G2, lambda d: "DUPLICATE_IGNORED" in events(d) and events(d).count("ADVISED") == 1)
check("C23 issuance-duplicate MX (PssblDplct)", lambda: post(sample("tsrv.001-issuance.xml", {"KEGTEE2026MX0001": X2, "KEMX0001": R("MG")})), [(MX19, "PssblDplct>true")])
activate("issuance-timeout")
check("C24 issuance-timeout (no reply)", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": G3})), [], wait=4, forbid=("MT768",))
lcheck("C24 ledger stays ISSUED", G3, lambda d: d["status"] == "ISSUED")
activate("happy-issuance")

# ================================================================ D. gateway / network layer
print("--- D. gateway")
for n, f in [("D1 NAK MT760 missing mandatory", "invalid-mt760-missing-fields.fin"), ("D1 NAK MT765 PAYM with 31E", "invalid-mt765-paym-with-31e.fin"),
             ("D1 NAK tsrv.001 schema", "invalid-tsrv001-missing-amount.xml")]:
    check(n, lambda f=f: post(sample(f)), [], expect_nak=True)
check("D1 NAK garbage", lambda: post("hello world"), [], expect_nak=True)
check("D1 NAK unknown receiver", lambda: post(MT760_G1.replace("I760TSTCKEN0XXXXN", "I760ZZZZKEN0XXXXN")), [], expect_nak=True)
check("D2 NAK X-charset violation (SIM-C01)", lambda: post(sample("mt768-ack-from-sut.fin", {"GBGTEE2026000777": G7, ":30:261029": ":30:261029\n:72Z:CAFÉ ÜBER"})), [], expect_nak=True, nak_code="SIM-C01")
check("D3 NAK field format 22A:ISSUEX (SIM-F10)", lambda: post(MT760_G1.replace(":22A:ISSU", ":22A:ISSUEX")), [], expect_nak=True, nak_code="SIM-F10")
mt202 = f"{{1:F01TSTAKEN0AXXX0000000000}}{{2:I202TSTBGB20XXXXN}}{{4:\n:20:{R('PAY')}\n:21:{G7}\n:32A:261029USD100,00\n:58A:{ISS}\n-}}"
check("D4 NAK MT202 without UETR {121:} (SIM-H21)", lambda: post(mt202), [], expect_nak=True, nak_code="SIM-H21")
check("D5 NAK output-format block 2 submitted (SIM-H03)", lambda: post(MT760_G1.replace("{2:I760TSTCKEN0XXXXN}", "{2:O7601200261029TSTCKEN0AXXX00000000002610291200N}")), [], expect_nak=True, nak_code="SIM-H03")
check("D6 NAK AppHdr MsgDefIdr mismatch (SIM-H11)", lambda: post(sample("tsrv.001-issuance.xml", {"KEGTEE2026MX0001": R("KXN"), "<MsgDefIdr>tsrv.001.001.01": "<MsgDefIdr>tsrv.005.001.01"})), [], expect_nak=True, nak_code="SIM-H11")
check("D7 NAK ?from conflicts with AppHdr (SIM-H12)", lambda: post(sample("tsrv.001-issuance.xml", {"KEGTEE2026MX0001": R("KXM")}), **{"from": "ISSUING_BANK"}), [], expect_nak=True, nak_code="SIM-H12")
h = []
check("D8 ACK + MIR/ISN", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": R("KG4")})), [("MT768", None)], holder=h)
h2 = []
check("D8 second submission", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": R("KG5")})), [("MT768", None)], holder=h2)
a1, a2 = h[0][1], h2[0][1]
record("D8 FIN-like ACK {451:0}, ISN increments, MIR 28 chars", "{451:0}" in a1["ack"] and a1["ack"].startswith("{1:F21TSTAKEN0AXXX")
       and a2["isn"] == a1["isn"] + 1 and len(a1["mir"]) == 28 and a1["sessionNo"] >= 1, f"isn {a1['isn']}->{a2['isn']} mir {a1['mir']}")
time.sleep(1.5)
_, ms = req("GET", f"/api/messages?to={SUT}&type=MT768&limit=1")
dr = ms[0].get("deliveredRaw") or ""
record("D9 delivered MT768: output block 2, MOR, {5:{CHK:}{TNG:}}", re.search(r"\{2:O768\d{10}[A-Z0-9]{12}\d{10}\d{10}N\}", dr) is not None and "{CHK:" in dr and "{TNG:}" in dr and ms[0]["mor"], dr[:120])
_, dl = req("GET", f"/api/messages?type=DLV&limit=1")
record("D10 delivery notification (MT011-like) references MIR", bool(dl) and a2["mir"] in (dl[0]["raw"] or "") if dl else False, (dl[0]["raw"] or "")[:150] if dl else "none")
pde = []
check("D11 PDE resend of processed message -> ignored", lambda: post(MT760_G1.rstrip() + "{5:{PDE:}}"), [], forbid=("MT768", MT759), wait=4, holder=pde)
record("D11 PDE flagged duplicate of original", pde and pde[0][1].get("possibleDuplicate") and pde[0][1].get("duplicateOf") is not None, pde[0][1].get("duplicateOf") if pde else None)
check("D12 PDE without original -> processed normally", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": R("KG6")}).rstrip() + "{5:{PDE:}}"), [("MT768", None)])
check("D13 MX without AppHdr (?from&to) tsrv.012 on MT undertaking", lambda: post(re.sub(r"<AppHdr.*?</AppHdr>", "", sample("tsrv.012-termination.xml", {"KEGTEE2026000001" if False else "KEGTEE2026MX0001": S1}), flags=re.S)
      .replace("<Envelope", "<Envelope").strip(), **{"from": "SUT_BANK", "to": "ADVISING_BANK"}), [(MX19, None)])
lcheck("D13 ledger S1 TERMINATED", S1, lambda d: d["status"] == "TERMINATED")
c, ss = req("GET", "/api/gateway/sessions")
record("D14 GET /api/gateway/sessions", c == 200 and any(x["bic"] == SUT and x["lastIsn"] >= 2 for x in ss), [x for x in ss if x["bic"] == SUT])
inbox = os.path.join(DATA, "inbox"); os.makedirs(inbox, exist_ok=True)
def drop():
    with open(os.path.join(inbox, f"e2e-{RUN}.fin"), "w") as f: f.write(sample("mt760-standby-to-advising.fin", {"KESBLC2026000001": S2}))
    return (200, {})
check("D15 file drop data/inbox -> MT768", drop, [("MT768", f":21:{S2}")], wait=10)
outbox = os.path.join(DATA, "outbox", SUT)
record("D16 outbox files written", os.path.isdir(outbox) and len(os.listdir(outbox)) > 0, outbox)
c, l = req("GET", f"/api/messages?to={SUT}&limit=5")
record("D17 GET /api/messages?to=SUT", c == 200 and len(l) > 0 and all(m["receiverBic"] == SUT for m in l), len(l))
c, ul = req("GET", "/api/undertakings")
record("D18 GET /api/undertakings lists run refs", c == 200 and {G1, G7, X1, X7} <= {u["ref"] for u in ul}, len(ul))
c, _ = req("GET", f"/api/undertakings/{R('NOPE')}")
record("D19 GET unknown undertaking -> 404", c == 404, c)

# ================================================================ E. manual / AI review mode
print("--- E. review queue")
def rq(ref, wait=5):
    deadline = time.time() + wait
    while time.time() < deadline:
        _, items = req("GET", "/api/review-queue")
        it = [i for i in items if i["txRef"] == ref and i["status"] == "PENDING_REVIEW"]
        if it: return it[-1]
        time.sleep(0.5)
    return None
def respond(item, body): return lambda: req("POST", f"/api/review-queue/{item['id'] if item else 0}/respond", body)
req("POST", "/api/participants/ADVISING_BANK/review-mode", {"enabled": True})
check("E1 review mode: MT760 queued, no auto answer", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": R1})), [], forbid=("MT768",), wait=3)
it = rq(R1)
record("E1 review item PENDING_REVIEW with ledger verdict", it is not None and it["ledgerVerdict"] == "APPLIED", it and {k: it[k] for k in ("id", "msgType", "ledgerVerdict", "suggestedActions")})
check("E2 respond ACCEPT -> MT768", respond(it, {"action": "ACCEPT", "note": "e2e"}), [("MT768", f":21:{R1}")])
lcheck("E2 ledger ADVISED", R1, lambda d: d["status"] == "ADVISED")
c, again = respond(it, {"action": "ACCEPT"})()
record("E2 second response on same item -> 409", c == 409, c)
post(amend_mt(R1, 1)); it = rq(R1)
check("E3 amendment: respond REFUSE with reason -> MT787 REJT", respond(it, {"action": "REFUSE", "reasons": ["AMOUNT INCREASE NOT AGREED"]}),
      [("MT787", ":23R:REJT"), ("MT787", "AMOUNT INCREASE NOT AGREED")])
lcheck("E3 ledger amendment REJECTED", R1, lambda d: d["amendments"][0]["status"] == "REJECTED")
post(amend_mt(R1, 5)); it = rq(R1)
record("E4 out-of-sequence amendment: verdict REJECTED BIZ-A02", it is not None and it["ledgerVerdict"] == "REJECTED" and "BIZ-A02" in (it["ledgerErrors"] or ""), it and it["ledgerErrors"])
check("E4 respond BUSINESS_REJECT -> MT759 with BIZ-A02", respond(it, {"action": "BUSINESS_REJECT"}), [(MT759, "BIZ-A02")])
post(amend_mt(R1, 2)); it = rq(R1)
check("E5 respond CUSTOM raw MT787 ACPT", respond(it, {"action": "CUSTOM", "mtType": "787", "raw": f":20:{R1}\n:21:{R('BEN')}\n:52A:{SUT}\n:26E:2\n:23R:ACPT"}), [("MT787", ":23R:ACPT")])
lcheck("E5 ledger AMENDED", R1, lambda d: d["status"] == "AMENDED" and d["amendments"][-1]["status"] == "ACCEPTED")
req("POST", "/api/participants/ADVISING_BANK/review-mode", {"enabled": False})
req("POST", "/api/participants/ISSUING_BANK/review-mode", {"enabled": True})
mxdem("tsrv.013-demand-paym.xml", R("XD6"), "10000.00"); it = rq(X7)
check("E6 MX demand in review: respond PAY -> tsrv.019 DMND", respond(it, {"action": "PAY"}), [(MX19, "DMND")])
lcheck("E6 ledger PAID outstanding 15000", X7, lambda d: d["status"] == "PAID" and d["outstanding"] == 15000)
req("POST", "/api/participants/ISSUING_BANK/review-mode", {"enabled": False})
c, allq = req("GET", "/api/review-queue?status=ALL")
record("E7 review queue history", c == 200 and sum(1 for i in allq if i["status"] == "RESPONDED") >= 5, len(allq))

# ================================================================ G-J. Stage 1-3 (guarantee gaps, documentary credits, collections)
exec(compile(open(os.path.join(ROOT, "tests", "e2e_trade_cases.py")).read(), "e2e_trade_cases.py", "exec"))
exec(compile(open(os.path.join(ROOT, "tests", "e2e_ops.py")).read(), "e2e_ops.py", "exec"))
exec(compile(open(os.path.join(ROOT, "tests", "e2e_audit.py")).read(), "e2e_audit.py", "exec"))

# ================================================================ F. restart persistence
if "--no-restart" not in sys.argv:
    print("--- F. restart persistence")
    activate("issuance-slow")
    check("F1 slow issuance accepted (response due in 15 s)", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": R2})), [], forbid=("MT768",), wait=2)
    _, pend = req("GET", "/api/pending-responses")
    record("F1 pending response persisted", any(p.get("txRef") == R2 or R2 in json.dumps(p) for p in pend), len(pend))
    _, before = req("GET", "/api/gateway/sessions")
    post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": R3}))   # stop immediately: delivery still due
    subprocess.run(STOP_CMD, shell=True, cwd=ROOT, check=False, capture_output=True)
    subprocess.run(START_CMD, shell=True, cwd=ROOT, check=False, capture_output=True)
    t0 = time.time()
    while not is_up() and time.time() - t0 < 60: time.sleep(1)
    record("F2 simulator restarted", is_up(), f"{time.time() - t0:.1f}s")
    lcheck("F3 ledger survived restart (G1 RELEASED, X1 TERMINATED)", G1, lambda d: d["status"] == "RELEASED" and ledger(X1)["status"] == "TERMINATED")
    lcheck("F4 delayed response fired after restart (R2 ADVISED)", R2, lambda d: d["status"] == "ADVISED", wait=25)
    lcheck("F5 undelivered message redelivered after restart", R3, lambda d: d["status"] in ("ISSUED", "ADVISED"), wait=25)
    _, after = req("GET", "/api/gateway/sessions")
    sb = lambda l: next(x["sessionNo"] for x in l if x["bic"] == SUT)
    record("F6 new LT session number after restart", sb(after) == sb(before) + 1, f"{sb(before)} -> {sb(after)}")
    activate("happy-issuance")

passed = sum(1 for r in results if r[1]); failed = [r for r in results if not r[1]]
print(f"\n{passed}/{len(results)} checks passed")
for r in failed: print("  FAILED:", r[0], "::", str(r[2])[:300])
sys.exit(0 if not failed else 1)
