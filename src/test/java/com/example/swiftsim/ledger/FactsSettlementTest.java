package com.example.swiftsim.ledger;

import com.example.swiftsim.parse.ParsedMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactsSettlementTest {
    @Test
    void mt103UsesField32ALikeMt202() {
        for (String type : new String[]{"MT103", "MT202"}) {
            ParsedMessage pm = new ParsedMessage();
            pm.setFormat("MT");
            pm.setMsgType(type);
            pm.getVars().put("f32A", "261110USD30000,00");
            Facts f = Facts.of(pm);
            assertEquals(new BigDecimal("30000.00"), f.amount, type);
            assertEquals("USD", f.currency, type);
        }
    }
}
