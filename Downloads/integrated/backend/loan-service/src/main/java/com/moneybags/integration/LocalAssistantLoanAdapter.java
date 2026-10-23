package com.moneybags.integration;

import com.fasterxml.jackson.databind.*;
import com.moneybags.loan.LoanController;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** In-process test harness adapter; production banking uses the HTTP boundary. */
@Component @Profile("local")
public class LocalAssistantLoanAdapter implements AssistantLoanPort {
    private final LoanController loans;private final LoanPrepaymentController prepayments;private final ObjectMapper json;
    public LocalAssistantLoanAdapter(LoanController loans,LoanPrepaymentController prepayments,ObjectMapper json){this.loans=loans;this.prepayments=prepayments;this.json=json;}
    public JsonNode read(Read operation,long id){
        var user=CurrentActor.get();Object value=switch(operation){
            case APPLICATIONS->loans.myApplications(user).data();case APPLICATION->loans.application(user,id).data();
            case OFFERS->loans.offers(user,id).data();case FACILITY->loans.facility(user,id).data();
            case SCHEDULE->loans.schedule(user,id).data();case PREPAYMENT_QUOTE->prepayments.quote(id);};
        return json.valueToTree(value);
    }
}
