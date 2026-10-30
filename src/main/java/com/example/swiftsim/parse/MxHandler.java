package com.example.swiftsim.parse;

import com.example.swiftsim.config.Bics;
import com.prowidesoftware.swift.model.mx.AbstractMX;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** ISO 20022 MX handling: official XSD validation (schemas from iso20022.org in classpath:xsd/) + Prowide ISO 20022 model parse. */
@Component
public class MxHandler {
    public static final String NS_PREFIX = "urn:iso:std:iso:20022:tech:xsd:";
    public static final Set<String> SUPPORTED = new LinkedHashSet<>(List.of(
            "tsrv.001.001.01", "tsrv.005.001.01", "tsrv.008.001.01", "tsrv.010.001.01", "tsrv.011.001.01",
            "tsrv.012.001.01", "tsrv.013.001.01", "tsrv.014.001.01", "tsrv.015.001.01", "tsrv.016.001.01",
            "tsrv.017.001.01", "tsrv.019.001.01", "tsin.005.001.01", "admi.002.001.01"));
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();

    public static boolean looksLikeMx(String raw) {
        return raw.stripLeading().startsWith("<");
    }

    public Schema schema(String msgId) {
        return schemas.computeIfAbsent(msgId, id -> {
            try {
                SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return sf.newSchema(new StreamSource(new ClassPathResource("xsd/" + id + ".xsd").getInputStream()));
            } catch (Exception e) {
                throw new IllegalStateException("cannot load XSD " + id + ": " + e.getMessage(), e);
            }
        });
    }

    public static Document parseDom(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b.parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }

    public static String toXml(Node n) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(n), new StreamResult(w));
        return w.toString();
    }

    static Element firstByLocalName(Node root, String name) {
        if (root == null) return null;
        NodeList all = root instanceof Document d ? d.getElementsByTagNameNS("*", name) : ((Element) root).getElementsByTagNameNS("*", name);
        return all.getLength() > 0 ? (Element) all.item(0) : null;
    }

    static String text(Node root, String... path) {
        Node cur = root;
        for (String p : path) {
            cur = firstByLocalName(cur, p);
            if (cur == null) return null;
        }
        return cur.getTextContent() == null ? null : cur.getTextContent().trim();
    }

    /** Validate an XML fragment against an XSD; returns error strings (max 10). */
    public List<String> validate(String msgId, String xml) {
        List<String> errs = new ArrayList<>();
        try {
            Validator v = schema(msgId).newValidator();
            v.setErrorHandler(new org.xml.sax.ErrorHandler() {
                public void warning(SAXParseException e) { }
                public void error(SAXParseException e) { if (errs.size() < 10) errs.add("line " + e.getLineNumber() + ": " + e.getMessage()); }
                public void fatalError(SAXParseException e) throws SAXParseException { throw e; }
            });
            v.validate(new StreamSource(new StringReader(xml)));
        } catch (Exception e) {
            errs.add(e.getMessage());
        }
        return errs;
    }

    public ParsedMessage parse(String raw) {
        ParsedMessage pm = new ParsedMessage();
        pm.setFormat("MX");
        pm.setNormalizedRaw(raw.strip());
        Document dom;
        try {
            dom = parseDom(raw.strip());
        } catch (Exception e) {
            pm.getErrors().add("SIM-X01 XML not well-formed: " + e.getMessage());
            return pm;
        }
        Element doc = firstByLocalName(dom, "Document");
        if (doc == null || doc.getNamespaceURI() == null || !doc.getNamespaceURI().startsWith(NS_PREFIX)) {
            pm.getErrors().add("SIM-X02 no ISO 20022 <Document> element found (namespace " + NS_PREFIX + "...)");
            return pm;
        }
        String msgId = doc.getNamespaceURI().substring(NS_PREFIX.length());
        pm.setMsgType(msgId);
        pm.setShortType(msgId.length() >= 8 ? msgId.substring(0, 8) : msgId);
        Element hdr = firstByLocalName(dom, "AppHdr");
        if (hdr != null) {
            pm.setSender(Bics.toBic11(text(firstByLocalName(hdr, "Fr"), "BICFI")));
            pm.setReceiver(Bics.toBic11(text(firstByLocalName(hdr, "To"), "BICFI")));
            String hns = hdr.getNamespaceURI();
            if ((NS_PREFIX + "head.001.001.02").equals(hns)) {
                try {
                    List<String> he = validate("head.001.001.02", toXml(hdr));
                    he.forEach(e -> pm.getErrors().add("SIM-X05 AppHdr (head.001.001.02) XSD: " + e));
                } catch (Exception e) {
                    pm.getWarnings().add("AppHdr not validated: " + e.getMessage());
                }
            } else {
                pm.getWarnings().add("AppHdr namespace " + hns + " not XSD-validated (only head.001.001.02 bundled)");
            }
            String pd = text(hdr, "PssblDplct");
            if ("true".equalsIgnoreCase(pd)) {
                pm.getWarnings().add("Possible duplicate flagged in AppHdr");
                pm.getVars().put("_pde", "true");
            }
            String bmi = text(hdr, "BizMsgIdr");
            if (bmi != null) pm.getVars().put("_mur", bmi);
            String mdi = text(hdr, "MsgDefIdr");
            if (mdi != null && !mdi.equals(msgId))
                pm.getErrors().add("SIM-H11 AppHdr MsgDefIdr '" + mdi + "' does not match the Document namespace message '" + msgId + "'");
            String cre = text(hdr, "CreDt");
            if (cre != null) {
                try {
                    java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(cre);
                    if (odt.isAfter(java.time.OffsetDateTime.now().plusDays(1)))
                        pm.getWarnings().add("AppHdr CreDt " + cre + " is more than one day in the future");
                } catch (Exception ignored) { /* XSD covers the format; local date-times without offset are allowed */ }
            }
            pm.getVars().put("_apphdr", "true");
        }
        if (!SUPPORTED.contains(msgId)) {
            pm.getErrors().add("SIM-T02 MX " + msgId + " not supported (supported: " + SUPPORTED + ")");
            return pm;
        }
        try {
            List<String> errs = validate(msgId, toXml(doc));
            errs.forEach(e -> pm.getErrors().add("SIM-X03 XSD " + msgId + ": " + e));
        } catch (Exception e) {
            pm.getErrors().add("SIM-X04 XSD validation failed: " + e.getMessage());
        }
        try {
            AbstractMX mx = AbstractMX.parse(raw.strip());
            pm.setProwideClass(mx == null ? null : mx.getClass().getName());
            if (mx == null) pm.getWarnings().add("Prowide could not build an MX model object");
        } catch (Exception e) {
            pm.getWarnings().add("Prowide MX parse: " + e.getMessage());
        }
        // vars
        Map<String, String> v = pm.getVars();
        String ref = text(doc, "UdrtkgId", "Id");
        if (ref == null) ref = text(doc, "UdrtkgIssncDtls", "Id");
        if (ref == null) ref = text(doc, "AmdmntId", "Id");
        if (ref == null) ref = text(doc, "ApplcntRefNb");
        pm.setTxRef(ref);
        v.put("ref", ref);
        String demandId = text(doc, "DmndDtls", "Id");
        if (demandId == null && firstByLocalName(doc, "UdrtkgDmndDtls") != null) {
            Element d = firstByLocalName(doc, "UdrtkgDmndDtls");
            for (Node c = d.getFirstChild(); c != null; c = c.getNextSibling())
                if (c instanceof Element e && "Id".equals(e.getLocalName())) { demandId = e.getTextContent().trim(); break; }
        }
        if (demandId != null) { v.put("demandId", demandId); pm.setRelatedRef(demandId); }
        Element amt = null;
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (amt == null && e.hasAttribute("Ccy")) amt = e;
            if (e.getFirstChild() != null && e.getFirstChild() == e.getLastChild() && e.getFirstChild().getNodeType() == Node.TEXT_NODE)
                v.putIfAbsent("x_" + e.getLocalName(), e.getTextContent().trim());
        }
        if (amt != null) {
            v.put("currency", amt.getAttribute("Ccy"));
            v.put("amountIso", amt.getTextContent().trim());
            v.put("amount", amt.getTextContent().trim().replace('.', ','));
        }
        String issuerBic = text(firstByLocalName(doc, "Issr"), "AnyBIC");
        if (issuerBic != null) v.put("issuerBic", issuerBic);
        String tp = text(doc, "UdrtkgDmndDtls", "Tp");
        if (firstByLocalName(doc, "UdrtkgDmndDtls") != null) {
            Element d = firstByLocalName(doc, "UdrtkgDmndDtls");
            for (Node c = d.getFirstChild(); c != null; c = c.getNextSibling())
                if (c instanceof Element e && "Tp".equals(e.getLocalName())) { tp = e.getTextContent().trim(); break; }
            if (tp != null) v.put("demandType", tp);
        }
        String seq = text(doc, "SeqNb");
        if (seq != null) v.put("amendmentNo", seq);
        String rx = text(doc, "ReqdXpryDt");
        if (rx != null) {
            v.put("newExpiryIso", rx);
            v.put("newExpiry6", rx.substring(2).replace("-", ""));
        }
        String nm = text(doc, "Nm");
        if (nm != null) v.put("formOfUndertaking", nm);
        facts(doc, v);
        return pm;
    }

    private static void put(Map<String, String> v, String k, String val) {
        if (val != null && !val.isBlank()) v.put(k, val.trim());
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling())
            if (c instanceof Element e && name.equals(e.getLocalName())) return e;
        return null;
    }

    /** Structured business facts used by the ledger (prefix mx_). */
    static void facts(Element doc, Map<String, String> v) {
        put(v, "mx_issuerBic", text(firstByLocalName(doc, "Issr"), "AnyBIC"));
        put(v, "mx_advisingBic", text(firstByLocalName(doc, "AdvsgPty"), "AnyBIC"));
        put(v, "mx_secondAdvisingBic", text(firstByLocalName(doc, "ScndAdvsgPty"), "AnyBIC"));
        Element bn = firstByLocalName(doc, "Bnfcry");
        put(v, "mx_beneficiaryBic", text(bn, "AnyBIC"));
        put(v, "mx_beneficiaryName", text(bn, "Nm"));
        Element ap = firstByLocalName(doc, "Applcnt");
        if (ap == null) ap = firstByLocalName(doc, "Oblgr");
        put(v, "mx_applicantBic", text(ap, "AnyBIC"));
        put(v, "mx_applicantName", text(ap, "Nm"));
        put(v, "mx_issueDate", text(doc, "DtOfIssnc"));
        Element xd = firstByLocalName(doc, "XpryDtls");
        if (xd != null) {
            put(v, "mx_expiryDate", text(xd, "XpryTerms", "DtTm", "Dt"));
            if (firstByLocalName(xd, "DtTm") != null) v.put("mx_expiryType", "FIXD");
            else if (firstByLocalName(xd, "Cond") != null) v.put("mx_expiryType", "COND");
            else if (firstByLocalName(xd, "OpnEnddInd") != null) v.put("mx_expiryType", "OPEN");
        }
        Element nx = firstByLocalName(doc, "NewXpryDtls");
        if (nx != null) put(v, "mx_newExpiryDate", text(nx, "XpryTerms", "DtTm", "Dt"));
        put(v, "mx_rules", text(doc, "GovncRulesAndLaw", "RuleId", "Cd"));
        Element ua = firstByLocalName(doc, "UdrtkgAmt");
        Element amt = ua == null ? null : firstByLocalName(ua, "Amt");
        if (amt != null) { v.put("mx_amount", amt.getTextContent().trim()); v.put("mx_currency", amt.getAttribute("Ccy")); }
        Element inc = firstByLocalName(doc, "IncrAmt");
        if (inc != null) { v.put("mx_increase", inc.getTextContent().trim()); v.put("mx_currency", inc.getAttribute("Ccy")); }
        Element dec = firstByLocalName(doc, "DecrAmt");
        if (dec != null) { v.put("mx_decrease", dec.getTextContent().trim()); v.put("mx_currency", dec.getAttribute("Ccy")); }
        put(v, "mx_consentRequired", text(doc, "BnfcryCnsntReqInd"));
        put(v, "mx_seqNb", text(doc, "SeqNb"));
        put(v, "mx_amendmentStatus", text(doc, "AmdmntSts"));
        Element dd = firstByLocalName(doc, "UdrtkgDmndDtls");
        if (dd != null) {
            Element id = child(dd, "Id"), tp = child(dd, "Tp");
            if (id != null) v.put("mx_demandId", id.getTextContent().trim());
            if (tp != null) v.put("mx_demandType", tp.getTextContent().trim());
            Element da = firstByLocalName(dd, "DmndAmt");
            Element a = da == null ? null : firstByLocalName(da, "Amt");
            if (a != null) { v.put("mx_demandAmount", a.getTextContent().trim()); v.put("mx_currency", a.getAttribute("Ccy")); }
            put(v, "mx_requestedExpiry", text(dd, "ReqdXpryDt"));
        }
        Element dm = firstByLocalName(doc, "DmndDtls");
        if (dm != null) {
            Element id = child(dm, "Id"), a = child(dm, "Amt");
            if (id != null) v.put("mx_demandId", id.getTextContent().trim());
            if (a != null) { v.put("mx_demandAmount", a.getTextContent().trim()); v.put("mx_currency", a.getAttribute("Ccy")); }
        }
        put(v, "mx_requestedExpiry", v.containsKey("mx_requestedExpiry") ? v.get("mx_requestedExpiry") : text(doc, "ReqdXpryDt"));
        put(v, "mx_statusCategory", text(doc, "StsCtgy"));
        // Sts directly under the details element (tsrv.015/016/019)
        Element root = null;
        for (Node c = doc.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) { root = e; break; }
        Element det = null;
        if (root != null) for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element e) { det = e; break; }
        Element sts = child(det, "Sts");
        if (sts != null) v.put("mx_status", sts.getTextContent().trim());
        put(v, "mx_effectiveDate", text(doc, "FctvDt"));
    }
}
