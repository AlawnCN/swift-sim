package com.example.swiftsim.gateway;

import com.example.swiftsim.domain.MessageRecord;
import com.example.swiftsim.ledger.LedgerService;
import com.example.swiftsim.parse.ParsedMessage;

public record DeliveredEvent(MessageRecord record, ParsedMessage parsed, SubmitOptions options, LedgerService.Outcome outcome) {}
