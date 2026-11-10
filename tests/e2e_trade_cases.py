# Stage 1-3 end-to-end cases. Executed inside e2e.py (before the restart phase) or alone via tests/e2e_trade.py.
# Uses the helpers of e2e.py: req, post, check, lcheck, trig, sample, R, ledger, msgs_since, last_id, record, activate.
REI, CNF, COL, REM, TRF = "TSTFUS30XXX", "TSTGKEN0XXX", "TSTHCN20XXX", "TSTJIN20XXX", "TSTKAE20XXX"
def lcd(ref):
    c, d = req("GET", f"/api/lcs/{ref}")
    return d if c == 200 else None
def cod(ref):
    c, d = req("GET", f"/api/collections/{ref}")
    return d if c == 200 else None
def tcheck(name, getter, ref, cond, wait=6.0):
    deadline, d = time.time() + wait, None
    while time.time() < deadline:
        d = getter(ref)
        try:
            if d and cond(d):
                return record(name, True, f"{ref} status={d['status']} outstanding={d.get('outstanding')}")
        except Exception: pass
        time.sleep(0.5)
    return record(name, False, f"{ref}: " + (json.dumps({k: d.get(k) for k in ('status', 'milestone', 'outstanding', 'amount', 'utilised', 'paidAmount', 'reimbClaimed', 'confirmed')}, default=str) if d else "not found"))
def kref(holder):
    """reference used by a kickoff (from the trigger response)"""
    try: return re.search(r"ref=(\S+)", " ".join(holder[-1][1]["scheduled"])).group(1)
    except Exception: return None
def last_raw(mt, frm=None, to=None, contains=None):
    _, l = req("GET", f"/api/messages?type={mt}&limit=20")
    for m in l:
        if (frm is None or m["senderBic"] == frm) and (to is None or m["receiverBic"] == to) and (contains is None or contains in (m["raw"] or "")):
            return m["raw"] or ""
    return ""
def f(raw, tag):
    m = re.search(r"\n:" + tag + r":([^\n]*)", "\n" + raw.split("{4:")[-1])
    return m.group(1).strip() if m else None
if "rq" not in globals():
    def rq(ref, wait=5):
        deadline = time.time() + wait
        while time.time() < deadline:
            _, items = req("GET", "/api/review-queue")
            it = [i for i in items if i["txRef"] == ref and i["status"] == "PENDING_REVIEW"]
            if it: return it[-1]
            time.sleep(0.5)
        return None
    def respond(item, body): return lambda: req("POST", f"/api/review-queue/{item['id'] if item else 0}/respond", body)
def amt(x): return ("%.2f" % x).replace(".", ",")
def pres(d, ref): return next((p for p in d["presentations"] if p["presentationRef"] == ref), None)
for p in ["ADVISING_BANK", "ISSUING_BANK", "COLLECTING_BANK", "REMITTING_BANK", "REIMBURSING_BANK"]:
    req("POST", f"/api/participants/{p}/review-mode", {"enabled": False})
for s in ["happy-issuance", "issuer-followup", "release-termination", "demand-paid", "lc-advice-ack", "lc-amendment-accepted", "lc-reimb-pays",
          "lc-issuer-pays", "coll-paid", "coll-tracer-answer", "coll-amend-noted", "coll-remitter-instructs"]: activate(s)

# ================================================================ G. Stage 1 - guarantee gaps
print("--- G. guarantee gaps (issuer-initiated amendment, reduction, withdrawal, query)")
h = []
check("G1 kickoff happy-issuance -> MT760 from ISSUING_BANK to SUT", trig("happy-issuance"), [("MT760", None)], holder=h)
U = kref(h)
lcheck("G1 SUT advising ledger ISSUED", U, lambda d: d["status"] in ("ISSUED", "ADVISED") and d["issuerBic"] == ISS)
hA = []
check("G2 kickoff issuer-amendment -> MT767 ISSUING_BANK -> SUT", trig("issuer-amendment", ref=U), [("MT767", f":20:{U}")], holder=hA)
lcheck("G2 ledger AMENDMENT_PENDING", U, lambda d: d["status"] == "AMENDMENT_PENDING")
check("G3 SUT MT768 while amendment pending -> issuer stays silent", lambda: post(sample("mt768-ack-issuer-amendment.fin", {"GBGTEE2026000777": U})), [], forbid=(MT759, "MT768"), wait=4)
check("G4 SUT MT787 ACPT -> issuer follow-up MT759 GENINFAD", lambda: post(sample("mt787-response-to-issuer-amendment.fin", {"GBGTEE2026000777": U})),
      [(MT759, ":23H:GENINFAD"), (MT759, "BENEFICIARY RESPONSE")])
lcheck("G4 ledger amendment ACCEPTED (AMENDED, amount 55000)", U, lambda d: d["status"] == "AMENDED" and d["amendments"][-1]["status"] == "ACCEPTED" and d["amount"] == 55000)
check("G5 kickoff issuer-reduction -> MT769 33B half / 34B rest", trig("issuer-reduction", ref=U), [("MT769", ":33B:USD27500,00"), ("MT769", ":34B:USD27500,00")])
lcheck("G5 ledger outstanding 27500", U, lambda d: d["outstanding"] == 27500)
req("POST", "/api/scenarios/happy-issuance/trigger?format=MX", {}); time.sleep(3)
_, ul = req("GET", "/api/undertakings")
UX = next((u["ref"] for u in ul if u["format"] == "MX" and u["issuerBic"] == ISS and u["status"] in ("ISSUED", "ADVISED")), None)
check("G6 MX issuer amendment tsrv.005 to SUT", trig("issuer-amendment", "MX", ref=UX), [("tsrv.005.001.01", UX)])
lcheck("G6 MX ledger AMENDMENT_PENDING", UX, lambda d: d["status"] == "AMENDMENT_PENDING")
# SUT = issuer: reduction with 34B consistency check
V = R("KGV")
check("G7 SUT issues MT760 (V)", lambda: post(sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": V})), [("MT768", f":21:{V}")])
check("G8 SUT MT769 33B/34B consistent -> MT768", lambda: post(sample("mt769-reduction-to-advising.fin", {"KEGTEE2026000001": V, "KERED0001": R("RD1")})), [("MT768", None)])
lcheck("G8 ledger outstanding 40000", V, lambda d: d["outstanding"] == 40000)
check("G9 SUT MT769 with wrong 34B -> BIZ-M03", lambda: post(sample("mt769-reduction-to-advising.fin", {"KEGTEE2026000001": V, "KERED0001": R("RD2"), ":34B:USD40000,00": ":34B:USD39000,00"})), [(MT759, "BIZ-M03")])
# demand withdrawal (SUT = issuer, beneficiary bank presents then withdraws)
W, W2 = R("KGW"), R("KGY")
for x in (W, W2): post(sample("mt760-standby-to-advising.fin", {"KESBLC2026000001": x}))
time.sleep(3)
check("G10 kickoff demand-withdrawal -> MT765 now", trig("demand-withdrawal", ref=W), [("MT765", f":20:{W}")])
lcheck("G10 ledger DEMAND_RECEIVED", W, lambda d: d["status"] == "DEMAND_RECEIVED")
check("G11 kickoff demand-paid (MT765) for W2", trig("demand-paid", ref=W2), [("MT765", f":20:{W2}")])
lcheck("G11 W2 demand pending", W2, lambda d: d["status"] == "DEMAND_RECEIVED")
check("G11 MX withdrawal of pending demand -> tsrv.017", trig("demand-withdrawal-pending", "MX", ref=W2), [("tsrv.017.001.01", W2)])
lcheck("G11 ledger demand WITHDRAWN, back to ISSUED/ADVISED", W2, lambda d: d["demands"][-1]["status"] == "WITHDRAWN" and d["status"] != "DEMAND_RECEIVED")
# query + answer (U) and query without answer (V, from the advising bank)
hq = []
check("G12 kickoff query-chaser -> MT759 QUERY", trig("query-chaser", ref=U), [(MT759, ":45D:QUERY")])
lcheck("G12 ledger open query", U, lambda d: d.get("openQueryRef"))
QREF = ledger(U)["openQueryRef"]
check("G13 SUT answers the query (MT759 :21: = query ref) -> no reply", lambda: post(sample("mt759-query-answer.fin", {"GBGTEE2026000777": U, "QUERYREF0001": QREF})), [], forbid=(MT759,), wait=4)
lcheck("G13 ledger QUERY_ANSWERED, no open query", U, lambda d: not d.get("openQueryRef") and "QUERY_ANSWERED" in events(d))
check("G14 query from ADVISING_BANK on V (left unanswered)", trig("query-chaser", ref=V, **{"from": "ADVISING_BANK"}), [(MT759, ":45D:QUERY")])
T_STAGE1 = time.time()

# ================================================================ H. Stage 2 - documentary credits (MT only)
print("--- H. documentary credits")
# ---- SUT = issuing bank
L = R("KL1")
LC700 = sample("mt700-lc-issue-to-advising.fin", {"KELC2026000001": L})
check("H1 SUT MT700 -> MT730 acknowledgement", lambda: post(LC700), [("MT730", f":21:{L}")])
tcheck("H1 LC ADVISED, max 105000 (39A 05/05), reimbursing bank", lcd, L, lambda d: d["status"] == "ADVISED" and d["maxAmount"] == 105000 and d["reimbursingBic"] == REI)
check("H2 duplicate credit number -> MT759 BIZ (LC)", lambda: post(LC700.replace("SAMPLE700001", "DUP700" + RUN)), [(MT759, "BIZ-")], forbid=("MT730",), wait=4)
check("H3 MT707 amendment 1 -> MT730 ack + /BENACC/", lambda: post(sample("mt707-lc-amendment.fin", {"KELC2026000001": L})), [("MT730", "/ACK/"), ("MT730", "/BENACC/")], wait=8)
tcheck("H3 LC AMENDED amount 110000", lcd, L, lambda d: d["amount"] == 110000 and d["amendments"][0]["status"] == "ACCEPTED")
A2 = sample("mt707-lc-amendment.fin", {"KELC2026000001": L, ":26E:1": ":26E:2"})
check("H4 MT707 amendment 2, beneficiary silent -> only MT730 ack", lambda: post(A2, scenario="lc-amendment-pending"), [("MT730", "/ACK/")], forbid=(("MT730", "/BENACC/"),), wait=5)
tcheck("H4 LC AMENDMENT_PENDING", lcd, L, lambda d: d["status"] == "AMENDMENT_PENDING")
check("H5 amendment while pending -> BIZ-A01", lambda: post(sample("mt707-lc-amendment.fin", {"KELC2026000001": L, ":26E:1": ":26E:3"})), [(MT759, "BIZ-A01")])
check("H6 MT707 wrong sequence -> BIZ-A02", lambda: post(sample("mt707-lc-amendment.fin", {"KELC2026000001": L, ":26E:1": ":26E:9"})), [(MT759, "BIZ-A0")])
check("H7 MT740 to reimbursing bank -> recorded, no reply", lambda: post(sample("mt740-lc-reimb-auth.fin", {"KELC2026000001": L})), [], forbid=("MT730", (MT759, "BIZ-")), wait=3)
tcheck("H7 LC reimbursement authorised (100000 + 39A tolerance)", lcd, L, lambda d: d["reimbAmount"] >= 100000)
check("H8 duplicate MT740 -> BIZ-L13", lambda: post(sample("mt740-lc-reimb-auth.fin", {"KELC2026000001": L}).replace("SAMPLE740001", "DUP740" + RUN)), [(MT759, "BIZ-L13")])
hp = []
check("H10 kickoff lc-presentation -> MT754 from nominated bank", trig("lc-presentation", ref=L), [("MT754", f":21:{L}")], holder=hp)
PR = f(last_raw("MT754", ADV, SUT, L), "20"); PA = lcd(L)
p0 = pres(PA, PR)
tcheck("H10 presentation recorded DOCS_PRESENTED", lcd, L, lambda d: d["status"] == "DOCS_PRESENTED" and pres(d, PR)["status"] in ("PRESENTED", "PENDING"))
PAMT = amt(p0["amount"]) if p0 else "0,00"
check("H11 SUT pays: MT756 + MT202 -> presentation PAID", lambda: (post(f":20:{R('PY1')}\n:21:{PR}\n:32B:USD{PAMT}\n:33A:{TODAY:%y%m%d}USD{PAMT}", **{"from": "SUT_BANK", "to": "ADVISING_BANK", "mtType": "756"}),
      post(f":20:{R('CV1')}\n:21:{PR}\n:32A:{TODAY:%y%m%d}USD{PAMT}\n:58A:{ADV}\n:72:/BNF/LC {L}", **{"from": "SUT_BANK", "to": "ADVISING_BANK", "mtType": "202"}))[0], [], forbid=((MT759, "BIZ-"),), wait=3)
tcheck("H11 presentation PAID, utilised", lcd, L, lambda d: pres(d, PR)["status"] == "PAID" and d["utilised"] > 0)
check("H12 SUT pays the same presentation again -> BIZ-L09", lambda: post(f":20:{R('PY2')}\n:21:{PR}\n:32B:USD{PAMT}\n:33A:{TODAY:%y%m%d}USD{PAMT}", **{"from": "SUT_BANK", "to": "ADVISING_BANK", "mtType": "756"}), [(MT759, "BIZ-L09")])
check("H13 kickoff over-drawing presentation (beyond 39A) -> MT754 to SUT", trig("lc-presentation-overdraw", ref=L), [("MT754", f":21:{L}")])
tcheck("H13 ledger flags the over-drawing (BIZ-L01 event, not booked)", lcd, L, lambda d: any("BIZ-L01" in (e.get("detail") or "") for e in d["history"]))
check("H14 kickoff duplicate presentation (PDE copy)", trig("lc-presentation-duplicate", ref=L), [("MT754", f":21:{L}")])
tcheck("H14 duplicate booked once (2 MT754 presentations in total)", lcd, L, lambda d: sum(1 for p in d["presentations"] if p["msgType"] == "MT754" and p["status"] != "REJECTED") == 2)
check("H15 kickoff discrepant presentation MT750", trig("lc-presentation-discrepant", ref=L), [("MT750", f":21:{L}")])
PR2 = f(last_raw("MT750", ADV, SUT, L), "20")
tcheck("H15 LC DISCREPANT", lcd, L, lambda d: d["status"] == "DISCREPANT")
p2 = pres(lcd(L), PR2)
check("H16 SUT refuses with MT734", lambda: post(f":20:{R('RF1')}\n:21:{PR2}\n:32A:{TODAY:%y%m%d}USD{amt(p2['amount']) if p2 else '1,00'}\n:77J:+LATE SHIPMENT\n:77B:/HOLD/", **{"from": "SUT_BANK", "to": "ADVISING_BANK", "mtType": "734"}), [], forbid=((MT759, "BIZ-"),), wait=3)
tcheck("H16 presentation REFUSED", lcd, L, lambda d: pres(d, PR2)["status"] == "REFUSED")
check("H9 kickoff claim ADVISING -> REIMBURSING (MT742) -> MT202 + MT799 debit advice to SUT", trig("lc-claim-to-reimbursing", ref=L),
      [("MT742", f":21:{L}"), ("MT202", None), ("MT799", "PAID")], wait=8)
tcheck("H9 LC reimbursement claimed", lcd, L, lambda d: (d["reimbClaimed"] or 0) > 0)

# ---- SUT = advising / nominated bank (virtual issuer)
hx = []
check("H17 kickoff lc-advise -> MT700 to SUT", trig("lc-advise"), [("MT700", None)], holder=hx)
X = kref(hx)
tcheck("H17 LC issued by ISSUING_BANK, SUT advising", lcd, X, lambda d: d["issuerBic"] == ISS)
check("H18 SUT MT730 ack to issuer -> no reply", lambda: post(sample("mt730-lc-ack-to-issuer.fin", {"KELC2026000101": X})), [], forbid=("MT730", (MT759, "BIZ-")), wait=3)
tcheck("H18 LC ADVISED", lcd, X, lambda d: d["status"] == "ADVISED")
check("H19 kickoff lc-amend-inbound -> MT707", trig("lc-amend-inbound", ref=X), [("MT707", f":23:{X}")])
check("H19 SUT MT730 /BENACC/ -> amendment accepted", lambda: post(sample("mt730-lc-benacc-to-issuer.fin", {"KELC2026000101": X})), [], forbid=((MT759, "BIZ-"),), wait=3)
tcheck("H19 LC amount 55000", lcd, X, lambda d: d["amount"] == 55000 and d["amendments"][0]["status"] == "ACCEPTED")
check("H20 SUT MT754 25000 -> issuer MT756 + MT202", lambda: post(sample("mt754-lc-presentation-to-issuer.fin", {"KELC2026000101": X, "KELCPRES0101": R("PX1")})),
      [("MT756", f":21:{R('PX1')}"), ("MT202", f":21:{R('PX1')}")], wait=8)
tcheck("H20 presentation PAID", lcd, X, lambda d: pres(d, R("PX1"))["status"] == "PAID")
check("H21 SUT over-draws (beyond 39A) -> issuer BIZ-L01", lambda: post(sample("mt754-lc-presentation-to-issuer.fin", {"KELC2026000101": X, "KELCPRES0101": R("PX2"), "USD25000,00": "USD99999,00"})), [(MT759, "BIZ-L01")], forbid=("MT756",))
check("H22 SUT MT750, issuer refuses -> MT734", lambda: post(sample("mt750-lc-discrepancy-to-issuer.fin", {"KELC2026000101": X, "KELCPRES0102": R("PX3"), "USD25000,00": "USD5000,00"}), scenario="lc-issuer-refuses"), [("MT734", f":21:{R('PX3')}")])
check("H23 duplicate presentation reference -> BIZ-L04", lambda: post(sample("mt754-lc-presentation-to-issuer.fin", {"KELC2026000101": X, "KELCPRES0101": R("PX1"), "USD25000,00": "USD1000,00"}).replace("SAMPLE754002", "DUP754" + RUN)), [(MT759, "BIZ-L04")])
check("H24 SUT MT754, issuer silent", lambda: post(sample("mt754-lc-presentation-to-issuer.fin", {"KELC2026000101": X, "KELCPRES0101": R("PX4"), "USD25000,00": "USD2000,00"}), scenario="lc-issuer-silent"), [], forbid=("MT756", "MT734"), wait=4)
check("H24 kickoff lc-issuer-payment pays it (MT756 + MT202)", trig("lc-issuer-payment", ref=X), [("MT756", R("PX4")), ("MT202", R("PX4"))], wait=6)
tcheck("H24 presentation PAID", lcd, X, lambda d: pres(d, R("PX4"))["status"] == "PAID")
hs = []
check("H25 kickoff lc-advise-strict (43P NOT ALLOWED, 00/00)", trig("lc-advise-strict"), [("MT700", ":43P:NOT ALLOWED")], holder=hs)
XS = kref(hs)
check("H25 SUT partial drawing -> BIZ-L02", lambda: post(sample("mt754-lc-presentation-to-issuer.fin", {"KELC2026000101": XS, "KELCPRES0101": R("PS1")})), [(MT759, "BIZ-L02")])
d0 = lcd(XS)
req("POST", "/api/admin/business-date", {"date": (datetime.date.fromisoformat(d0["expiryDate"]) + datetime.timedelta(days=3)).isoformat()})
check("H26 presentation after expiry -> BIZ-L03", lambda: post(sample("mt754-lc-presentation-to-issuer.fin", {"KELC2026000101": XS, "KELCPRES0101": R("PS2"), "USD25000,00": "USD50000,00"})), [(MT759, "BIZ-L03")])
tcheck("H26 LC EXPIRED by the business-date sweep", lcd, XS, lambda d: d["status"] == "EXPIRED")
req("DELETE", "/api/admin/business-date")
hc = []
check("H27 kickoff lc-advise-confirm (49 CONFIRM)", trig("lc-advise-confirm"), [("MT700", ":49:CONFIRM")], holder=hc)
XC = kref(hc)
check("H27 SUT MT730 /CONFIRMED/", lambda: post(sample("mt730-lc-confirmed-to-issuer.fin", {"KELC2026000101": XC})), [], forbid=((MT759, "BIZ-"),), wait=3)
tcheck("H27 LC CONFIRMED", lcd, XC, lambda d: d["confirmed"] is True)
hr = []
check("H28 kickoff lc-advise-reimb (53A reimbursing bank)", trig("lc-advise-reimb"), [("MT700", f":53A:{REI}")], holder=hr)
XR = kref(hr)
check("H28 SUT claims on reimbursing bank MT742 without MT740 -> BIZ-L20", lambda: post(sample("mt742-lc-claim-to-reimbursing.fin", {"KELC2026000101": XR, "KELCCLM0101": R("CL1")})), [(MT759, "BIZ-L20")])
ht = []
check("H29 kickoff lc-advise-transferable", trig("lc-advise-transferable"), [("MT700", "TRANSFERABLE")], holder=ht)
XT = kref(ht)
def _xt_expiry():  # transferred credit must not expire after the original (BIZ-L15); scenario expiry depends on today's date
    d, deadline = None, time.time() + 8   # also waits until the MT700 has been delivered (ledger record exists)
    while time.time() < deadline and not d:
        d = lcd(XT)
        if not d: time.sleep(0.3)
    e = (d or {}).get("expiryDate")
    return e[2:4] + e[5:7] + e[8:10] if e else "270430"
check("H29 SUT transfers with MT720 -> transferee MT730", lambda: post(sample("mt720-lc-transfer.fin", {"KELC2026000101": XT, "KELCTRF0101": R("TR1"), ":31D:270430": ":31D:" + _xt_expiry()})), [("MT730", None)])
tcheck("H29 LC transferred amount 30000", lcd, XT, lambda d: d["transferredAmount"] == 30000)
check("H30 MT720 on a non-transferable LC -> BIZ-L10", lambda: post(sample("mt720-lc-transfer.fin", {"KELC2026000101": X, "KELCTRF0101": R("TR2")})), [(MT759, "BIZ-L10")])
hp2 = []
check("H31 kickoff lc-pre-advice -> MT705 now, MT700 after 10 s", trig("lc-pre-advice"), [("MT705", None), ("MT700", None)], wait=14, holder=hp2)
tcheck("H31 LC ISSUED after pre-advice", lcd, kref(hp2), lambda d: d["status"] in ("ISSUED", "ADVISED") and "PRE_ADVISED" in [e["eventType"] for e in d["history"]])
htr = []
check("H32 kickoff lc-transfer-inbound -> MT700 (internal) + MT720 to SUT", trig("lc-transfer-inbound"), [("MT700", None), ("MT720", None)], wait=10, holder=htr)
check("H33 kickoff lc-third-bank-advice -> MT710", trig("lc-third-bank-advice"), [("MT710", None)])
hm = []
check("H34 kickoff lc-reimb-auth-inbound -> MT740 to SUT", trig("lc-reimb-auth-inbound"), [("MT740", None)], holder=hm)
XM = kref(hm)
check("H35 kickoff lc-claim-inbound -> MT742 to SUT", trig("lc-claim-inbound", ref=XM), [("MT742", f":21:{XM}")])
CR = f(last_raw("MT742", ADV, SUT, XM), "20")
check("H35 SUT reimburses with MT202", lambda: post(f":20:{R('RB1')}\n:21:{CR}\n:32A:{TODAY:%y%m%d}USD50000,00\n:58A:{ADV}\n:72:/BNF/LC {XM}", **{"from": "SUT_BANK", "to": "ADVISING_BANK", "mtType": "202"}), [], forbid=((MT759, "BIZ-"),), wait=3)
tcheck("H35 claim REIMBURSED", lcd, XM, lambda d: pres(d, CR)["status"] in ("REIMBURSED", "PAID"))
check("H36 kickoff lc-claim-overclaim -> MT742 above authorisation", trig("lc-claim-overclaim", ref=XM), [("MT742", f":21:{XM}")])
check("H37 kickoff lc-reimb-amend-inbound -> MT747", trig("lc-reimb-amend-inbound", ref=XM), [("MT747", f":20:{XM}")])
tcheck("H37 reimbursement amount increased", lcd, XM, lambda d: d["reimbAmount"] > 50000)
# review mode on an LC ledger
req("POST", "/api/participants/ADVISING_BANK/review-mode", {"enabled": True})
L2 = R("KL2")
check("H38 review mode: SUT MT700 queued", lambda: post(sample("mt700-lc-issue-to-advising.fin", {"KELC2026000001": L2})), [], forbid=("MT730",), wait=3)
it = rq(L2)
record("H38 LC review item with suggested actions", it is not None and "ACK" in (it.get("suggestedActions") or "").split(","), it and it.get("suggestedActions"))
check("H39 respond ACK -> MT730", respond(it, {"action": "ACK"}), [("MT730", f":21:{L2}")])
req("POST", "/api/participants/ADVISING_BANK/review-mode", {"enabled": False})
c, lst = req("GET", "/api/lcs")
record("H40 GET /api/lcs lists run credits", c == 200 and {L, X} <= {x["ref"] for x in lst}, len(lst))

# ================================================================ I. Stage 3 - documentary collections (MT only)
print("--- I. collections")
def csample(name, ref, extra=None):
    """SUT-as-collecting-bank sample with the kickoff's collection number and amount"""
    d, t0 = cod(ref), time.time()
    while d is None and time.time() - t0 < 6: time.sleep(0.5); d = cod(ref)
    a = "USD" + amt(d["amount"])
    return sample(name, {"KECO2026000101": ref, "USD80000,00": a, **(extra or {})})
def collinst(ref, typ="DP", amount="80000,00", tenor="AT SIGHT", partial=False, **kw):
    s = sample("mt499-coll-instruction-dp.fin", {"KECO2026000001": ref, "/COLLINST/DP": f"/COLLINST/{typ}", "USD80000,00": f"USD{amount}", "/TENOR/AT SIGHT": f"/TENOR/{tenor}"})
    if partial:
        s = s.replace("/CHARGES/DRAWEE", "/PARTIAL/YES\n/CHARGES/DRAWEE")
    return lambda: post(s, **kw)
C1, C2, C3, C4, C5 = (R(t) for t in ["KC1", "KC2", "KC3", "KC4", "KC5"])
check("I1 SUT D/P instruction -> MT410 + MT400 + MT202", collinst(C1), [("MT410", f":21:{C1}"), ("MT400", f":21:{C1}"), ("MT202", f":21:{C1}")], wait=10)
tcheck("I1 collection PAID", cod, C1, lambda d: d["status"] == "PAID" and d["paidAmount"] == 80000 and d["outstanding"] == 0)
check("I2 duplicate collection reference -> MT499 BIZ-K02", collinst(C1), [("MT499", "BIZ-K02")], forbid=("MT410",), wait=4)
check("I3 SUT D/A instruction -> MT410 + MT412 + MT400 + MT202", collinst(C2, "DA", "60000,00", "60 DAYS AFTER SIGHT"), [("MT410", None), ("MT412", f":21:{C2}"), ("MT400", f":21:{C2}"), ("MT202", f":21:{C2}")], wait=15)
tcheck("I3 D/A collection accepted then PAID", cod, C2, lambda d: d["status"] == "PAID" and d["acceptedAmount"] == 60000)
check("I4 D/A without tenor -> BIZ-K04", lambda: post(sample("mt499-coll-instruction-dp.fin", {"KECO2026000001": R("KC9"), "/COLLINST/DP": "/COLLINST/DA", "/TENOR/AT SIGHT\n": ""})), [("MT499", "BIZ-K04")])
check("I5 instruction, non-payment -> MT410 + MT416", collinst(C3, scenario="coll-non-payment"), [("MT410", None), ("MT416", f":21:{C3}")], wait=8)
tcheck("I5 collection NON_PAID", cod, C3, lambda d: d["status"] == "NON_PAID")
check("I6 SUT MT430 /RETURN/ -> MT422 /RETURNED/", lambda: post(sample("mt430-coll-return.fin", {"KECO2026000001": C3})), [("MT422", "/RETURNED/")])
tcheck("I6 collection RETURNED", cod, C3, lambda d: d["status"] == "RETURNED")
check("I7 instruction, partial payment -> MT400 half", collinst(C4, scenario="coll-partial", partial=True), [("MT410", None), ("MT400", ":32B:USD40000,00"), ("MT202", ":32A:")], wait=9)
tcheck("I7 collection PARTIALLY_PAID 40000 outstanding", cod, C4, lambda d: d["status"] == "PARTIALLY_PAID" and d["outstanding"] == 40000)
check("I8 instruction, collecting bank silent", collinst(C5, scenario="coll-no-response"), [], forbid=("MT410",), wait=3)
check("I9 SUT MT420 tracer -> MT422 fate", lambda: post(sample("mt420-coll-tracer.fin", {"KECO2026000001": C5})), [("MT422", f":21:{C5}")])
tcheck("I9 tracer counted and answered", cod, C5, lambda d: d["tracerCount"] == 1 and not d["tracerPending"])
check("I10 SUT MT430 amendment -> MT499 noted", lambda: post(sample("mt430-coll-amend.fin", {"KECO2026000001": C5, "261230": f"{TODAY:%y%m%d}"})), [("MT499", "NOTED")])
tcheck("I10 amount reduced to 70000", cod, C5, lambda d: d["amount"] == 70000)
check("I11 kickoff coll-payment (collecting bank pays C5) -> MT400 + MT202", trig("coll-payment", ref=C5), [("MT400", f":21:{C5}"), ("MT202", f":21:{C5}")], wait=6)
tcheck("I11 C5 PAID", cod, C5, lambda d: d["status"] == "PAID")
check("I12 kickoff coll-adjustment-inbound MT455", trig("coll-adjustment-inbound", ref=C5), [("MT455", f":21:{C5}")])
# ---- SUT = collecting bank
hd = []
check("I13 kickoff coll-instruction-dp -> MT499 /COLLINST/DP to SUT", trig("coll-instruction-dp"), [("MT499", "/COLLINST/DP")], holder=hd)
D1 = kref(hd)
tcheck("I13 SUT collecting ledger SENT", cod, D1, lambda d: d["collectingBic"] == SUT and d["status"] == "SENT")
check("I14 SUT MT410 ack -> no reply", lambda: post(csample("mt410-coll-ack-to-remitting.fin", D1)), [], forbid=("MT430", "MT499"), wait=3)
tcheck("I14 ACKNOWLEDGED", cod, D1, lambda d: d["status"] == "ACKNOWLEDGED")
check("I15 SUT over-payment MT400 -> BIZ-K01", lambda: post(sample("mt400-coll-payment-to-remitting.fin", {"KECO2026000101": D1, "USD80000,00": "USD" + amt(cod(D1)["amount"] + 10000)})), [("MT499", "BIZ-K01")])
check("I16 SUT MT416 -> remitting bank MT430 /RETURN/", lambda: post(csample("mt416-coll-non-payment-to-remitting.fin", D1)), [("MT430", "/RETURN/")])
check("I17 SUT MT422 /RETURNED/ -> closed, no reply", lambda: post(csample("mt422-coll-returned-to-remitting.fin", D1)), [], forbid=("MT430",), wait=3)
tcheck("I17 RETURNED", cod, D1, lambda d: d["status"] == "RETURNED")
ha = []
check("I18 kickoff coll-instruction-da", trig("coll-instruction-da"), [("MT499", "/COLLINST/DA")], holder=ha)
D2 = kref(ha)
tcheck("I18 D/A collection SENT to SUT", cod, D2, lambda d: d["collType"] == "DA")
check("I19 SUT MT412 acceptance + MT400 + MT202", lambda: (post(csample("mt412-coll-acceptance-to-remitting.fin", D2)),
      post(csample("mt400-coll-payment-to-remitting.fin", D2)), post(csample("mt202-coll-cover-to-remitting.fin", D2)))[0], [], forbid=("MT499",), wait=4)
tcheck("I19 D/A collection ACCEPTED then PAID", cod, D2, lambda d: d["status"] == "PAID" and d["acceptedAmount"] == d["amount"])
check("I20 MT412 on D/P collection -> BIZ-K06", lambda: post(csample("mt412-coll-acceptance-to-remitting.fin", D1)), [("MT499", "BIZ-K")])
hz = []
check("I21 kickoff coll-instruction-clean + coll-tracer-inbound + coll-amend-inbound", trig("coll-instruction-clean"), [("MT499", "/COLLINST/CLEAN")], holder=hz)
D3 = kref(hz)
tcheck("I21 clean collection recorded", cod, D3, lambda d: d["collType"] == "CLEAN")
check("I21 tracer MT420 to SUT", trig("coll-tracer-inbound", ref=D3), [("MT420", f":20:{D3}")])
check("I21 amendment MT430 to SUT", trig("coll-amend-inbound", ref=D3), [("MT430", f":20:{D3}")])
tcheck("I21 amount halved by MT430", cod, D3, lambda d: d["amount"] * 2 == d["originalAmount"] and d["tracerCount"] == 1)
check("I22 SUT MT422 fate -> remitting bank MT430 amendment", lambda: post(csample("mt422-coll-fate-to-remitting.fin", D3)), [("MT430", f":20:{D3}")])
# review mode on a collection ledger
req("POST", "/api/participants/COLLECTING_BANK/review-mode", {"enabled": True})
C6 = R("KC6")
check("I23 review mode: instruction queued", collinst(C6), [], forbid=("MT410",), wait=3)
it = rq(C6)
record("I23 collection review item suggests ACK/PAY", it is not None and {"ACK", "PAY"} <= set((it.get("suggestedActions") or "").split(",")), it and it.get("suggestedActions"))
check("I24 respond PAY -> MT400 + MT202", respond(it, {"action": "PAY"}), [("MT400", f":21:{C6}"), ("MT202", f":21:{C6}")], wait=6)
tcheck("I24 C6 PAID", cod, C6, lambda d: d["status"] == "PAID")
req("POST", "/api/participants/COLLECTING_BANK/review-mode", {"enabled": False})
c, lst = req("GET", "/api/collections?status=PAID")
record("I25 GET /api/collections?status=PAID", c == 200 and C1 in {x["ref"] for x in lst}, len(lst))
C7 = R("KC7")
check("I26 partial payment not authorised -> no auto reply", collinst(C7, scenario="coll-no-response"), [], forbid=("MT410",), wait=3)
_half = []
check("I26 MT400 half submitted to SUT", lambda: post(
    f":20:{R('HP')}\n:21:{C7}\n:32B:USD40000,00\n:33A:{TODAY:%y%m%d}USD40000,00",
    **{"from": "COLLECTING_BANK", "to": "SUT_BANK", "mtType": "400"}), [], holder=_half)
def _k13():
    mid = (_half[0][1] or {}).get("id") if _half else None
    deadline = time.time() + 6
    while time.time() < deadline and mid:
        _, m = req("GET", f"/api/messages/{mid}")
        reason = (m or {}).get("businessReason") or ""
        if "BIZ-K13" in reason:
            return reason
        time.sleep(0.4)
    return (m or {}).get("businessReason") if mid else "no message"
record("I26 MT400 half without /PARTIAL/ is BIZ-K13 (SUT should reject)", "BIZ-K13" in (_why := _k13()), _why)

# ================================================================ J. delayed chasers (fireWhen)
print("--- J. delayed chasers")
time.sleep(max(0, 50 - (time.time() - T_STAGE1)))
_, pend = req("GET", "/api/pending-responses?all=true")
def pstat(tpl, ref): return [p["status"] for p in pend if p.get("template") == tpl and p.get("ref") == ref]
deadline = time.time() + 20
while time.time() < deadline and "DONE" not in pstat("mt759-chaser.fin", V):
    time.sleep(1); _, pend = req("GET", "/api/pending-responses?all=true")
record("J1 query chaser sent for unanswered query (V)", any(s not in ("PENDING", "SKIPPED") for s in pstat("mt759-chaser.fin", V)), pstat("mt759-chaser.fin", V))
record("J2 query chaser skipped for answered query (U)", pstat("mt759-chaser.fin", U) == ["SKIPPED"], pstat("mt759-chaser.fin", U))
deadline = time.time() + 30
while time.time() < deadline and "PENDING" in pstat("mt759-amend-chaser.fin", U):
    time.sleep(1); _, pend = req("GET", "/api/pending-responses?all=true")
record("J3 issuer amendment chaser skipped (SUT answered MT787)", pstat("mt759-amend-chaser.fin", U) == ["SKIPPED"], pstat("mt759-amend-chaser.fin", U))
lcheck("J5 demand withdrawn by the delayed MT759 (W)", W, lambda d: d["demands"][-1]["status"] == "WITHDRAWN", wait=5)
