# Audit fixes (real problems + simulator-sensible partials). Runs inside e2e.py.
print("--- K. audit fixes")
KA, KB, KC, KL = (R(t) for t in ["KA1", "KB1", "KC1", "KL1"])

bad_date = sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": KA, ":30:261029": ":30:260231"})
check("K1 invalid date 260231 NAK SIM-F03", lambda: post(bad_date), [], expect_nak=True, nak_code="SIM-F03")
bad_ccy = sample("mt760-issue-to-advising.fin", {"KEGTEE2026000001": KA, "USD50000,00": "ZZZ50000,00"})
check("K2 currency ZZZ NAK SIM-F07", lambda: post(bad_ccy), [], expect_nak=True, nak_code="SIM-F07")
bad_stby = sample("mt760-standby-to-advising.fin", {"KESBLC2026000001": KB}).replace(":49:WITHOUT\n", "")
check("K3 ISSU+STBY without :49: NAK SIM-N63", lambda: post(bad_stby), [], expect_nak=True, nak_code="SIM-N63")
bad_isco = sample("mt760-counter-guarantee.fin", {"KECOUNTER000001": KC, "KELOCAL0000001": KL})
bad_isco = bad_isco.split(":15C:")[0] + "-}"
check("K4 ISCO without sequence C NAK SIM-N61", lambda: post(bad_isco), [], expect_nak=True, nak_code="SIM-N61")

isco = sample("mt760-counter-guarantee.fin", {"KECOUNTER000001": KC, "KELOCAL0000001": KL})
check("K5 ISCO counter-guarantee -> MT768", lambda: post(isco), [("MT768", f":21:{KC}")])
lcheck("K5 counter obligation linked to local", KC, lambda d: d["obligationKind"] == "COUNTER" and d["relatedRef"] == KL and d["purpose"] == "ISCO")
lcheck("K5 local obligation REQUESTED", KL, lambda d: d["status"] == "REQUESTED" and d["obligationKind"] == "LOCAL"
       and d["relatedRef"] == KC and d["currency"] == "KES" and d["amount"] == 10000000)

check("K6 standby for partial payment -> MT768", lambda: post(sample("mt760-standby-to-advising.fin", {"KESBLC2026000001": KB})), [("MT768", f":21:{KB}")])
check("K6 beneficiary demand to SUT", trig("demand-paid", ref=KB), [("MT765", f":20:{KB}")])
lcheck("K6 demand received for full outstanding", KB, lambda d: d["status"] == "DEMAND_RECEIVED" and d["demands"][-1]["amount"] == 120000)
pacs = sample("pacs.009-core-cover.xml", {
    "TSTBGB20XXX": SUT, "TSTDKEN0XXX": BEN, "KEGTEE2026000001": KB, "25000.00": "60000.00", "PACS9CORE0001": R("P9A")})
# sender BIC was replaced globally, including InstgAgt; receiver beneficiary is BEN. Fix the second agent back if the replace hit both.
check("K6 pacs.009 CORE partial settlement", lambda: post(pacs), [], forbid=("NAK",), wait=3)
lcheck("K6 demand PARTIALLY_PAID, outstanding 60000", KB, lambda d: d["status"] == "PARTIALLY_PAID" and d["outstanding"] == 60000
       and d["demands"][-1]["status"] == "PARTIALLY_PAID" and d["demands"][-1]["paidAmount"] == 60000)
pacs2 = sample("pacs.009-core-cover.xml", {
    "TSTBGB20XXX": SUT, "TSTDKEN0XXX": BEN, "KEGTEE2026000001": KB, "25000.00": "60000.00", "PACS9CORE0001": R("P9B")})
check("K7 pacs.009 pays the remainder", lambda: post(pacs2), [], forbid=("NAK",), wait=3)
lcheck("K7 demand PAID, outstanding 0", KB, lambda d: d["status"] == "PAID" and d["outstanding"] == 0 and d["demands"][-1]["status"] == "PAID")

cont = ("{1:F01TSTAKEN0AXXX0000000000}{2:I761TSTCKEN0XXXXN}{4:\n"
        f":27:2/2\n:20:{KB}\n:52A:{SUT}\n:77U:CONTINUATION OF STANDBY TERMS\nLINE TWO OF THE NARRATIVE\n-}}")
check("K8 MT761 continuation ACK", lambda: post(cont), [], forbid=("NAK",), wait=3)
lcheck("K8 continuation stored", KB, lambda d: "CONTINUATION" in events(d) and d.get("additionalTerms") and "CONTINUATION OF STANDBY" in d["additionalTerms"])

check("K9 positive pacs.008 sample ACK", lambda: post(sample("pacs.008-customer-payment.xml")), [], forbid=("NAK",), wait=2)
check("K9 positive pacs.009 COV sample ACK", lambda: post(sample("pacs.009-cov-cover.xml")), [], forbid=("NAK",), wait=2)
