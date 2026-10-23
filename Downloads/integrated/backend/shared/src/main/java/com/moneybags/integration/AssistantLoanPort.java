package com.moneybags.integration;

import com.fasterxml.jackson.databind.JsonNode;

/** Read-only loan boundary. The model cannot supply an arbitrary service URL. */
public interface AssistantLoanPort {
    enum Read { APPLICATIONS, APPLICATION, OFFERS, FACILITY, SCHEDULE, PREPAYMENT_QUOTE }
    JsonNode read(Read operation,long id);
}
