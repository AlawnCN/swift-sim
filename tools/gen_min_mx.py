#!/usr/bin/env python3
"""Generate a minimal XSD-valid instance for an ISO 20022 schema (dev helper used to bootstrap MX templates)."""
import sys, xmlschema
from xmlschema.validators import XsdElement, XsdGroup, XsdAnyElement

PREF = ["BICFI", "AnyBIC", "BIC", "Cd", "Id", "IBAN", "Dt", "Amt"]

def simple_value(st, name):
    n = (st.name or '').split('}')[-1] if st is not None else ''
    if st is not None and getattr(st, 'enumeration', None):
        return str(st.enumeration[0])
    base = st
    while base is not None and getattr(base, 'enumeration', None) is None and base.base_type is not None and not base.is_simple() :
        base = base.base_type
    tn = n
    if 'BIC' in tn: return 'TESTKENAXXX'
    if 'IBAN' in tn: return 'KE12TEST0000000000000000'
    if 'CountryCode' in tn: return 'KE'
    if 'CurrencyCode' in tn: return 'USD'
    if 'ISODateTime' in tn: return '2026-10-29T10:00:00'
    if 'ISODate' in tn: return '2026-10-29'
    if 'YesNo' in tn or 'Indicator' in tn: return 'true'
    if 'Number' in tn or 'Decimal' in tn or 'Rate' in tn: return '1'
    if 'Amount' in tn: return '1000.00'
    if 'Max4AlphaNumeric' in tn: return 'A1'
    if 'Exact4AlphaNumeric' in tn: return 'ABCD'
    if 'Exact2' in tn: return 'AB'
    if 'Exact3' in tn: return 'ABC'
    if 'Exact4' in tn: return 'ABCD'
    if 'Base64' in tn or 'Binary' in tn: return 'AAAA'
    if 'LEI' in tn: return '529900T8BM49AURSDO55'
    if 'Max' in tn and 'Numeric' in tn: return '1'
    return 'TEST'

def gen(el, depth=0):
    pad = '  ' * depth
    t = el.type
    tag = el.local_name
    if t.is_simple():
        return f"{pad}<{tag}>{simple_value(t, tag)}</{tag}>\n"
    attrs = ''
    for an, a in t.attributes.items():
        if a.use == 'required':
            attrs += f' {an}="{simple_value(a.type, an)}"'
    if t.has_simple_content():
        st = t.content
        v = simple_value(st, tag)
        if 'Amount' in (t.name or ''): v = '1000.00'
        return f"{pad}<{tag}{attrs}>{v}</{tag}>\n"
    body = gen_group(t.content, depth + 1)
    return f"{pad}<{tag}{attrs}>\n{body}{pad}</{tag}>\n"

def gen_group(g, depth):
    out = ''
    if g.model == 'choice':
        items = [i for i in g if not isinstance(i, XsdAnyElement)]
        def score(i):
            if isinstance(i, XsdElement):
                return PREF.index(i.local_name) if i.local_name in PREF else 99
            return 50
        items.sort(key=score)
        it = items[0]
        return gen(it, depth) if isinstance(it, XsdElement) else gen_group(it, depth)
    for it in g:
        if isinstance(it, XsdAnyElement):
            continue
        if it.min_occurs >= 1:
            out += gen(it, depth) if isinstance(it, XsdElement) else gen_group(it, depth)
    return out

if __name__ == '__main__':
    s = xmlschema.XMLSchema(sys.argv[1])
    root = s.elements['Document']
    xml = gen(root).replace('<Document>', f'<Document xmlns="{s.target_namespace}">', 1)
    print(xml)
    errs = list(s.iter_errors(xml))
    for e in errs[:5]:
        print('ERR', e.reason, e.path, file=sys.stderr)
