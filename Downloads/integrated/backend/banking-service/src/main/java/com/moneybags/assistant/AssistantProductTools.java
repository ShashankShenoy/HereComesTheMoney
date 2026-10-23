package com.moneybags.assistant;

import com.fasterxml.jackson.databind.*;
import com.moneybags.integration.*;
import com.moneybags.creditcard.CreditCardService;
import com.moneybags.product.controller.ProductController;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.moneybags.assistant.AssistantTools.*;

/** Product reads reuse authoritative services. Writes only open secure, permission-scoped forms. */
@Service
public class AssistantProductTools {
    private final AssistantLoanPort loans;private final BankingController banking;
    private final CreditCardService cards;private final TermDepositController deposits;
    private final ProductController products;private final ObjectMapper json;
    public AssistantProductTools(AssistantLoanPort loans,BankingController banking,CreditCardService cards,
            TermDepositController deposits,ProductController products,ObjectMapper json){
        this.loans=loans;this.banking=banking;this.cards=cards;this.deposits=deposits;this.products=products;this.json=json;
    }
    static final List<Definition> DEFINITIONS=List.of(
        def("list_my_product_offers","List eligible account, loan and fixed deposit products and their actual terms for the customer's linked profiles.","CUSTOMER","PRODUCT_READ"),
        def("list_my_loan_applications","List the customer's submitted loan applications and their exact statuses.","CUSTOMER","LOAN_READ"),
        def("get_loan_application","Read a permitted loan application. This does not approve it.","CUSTOMER","LOAN_READ",property("applicationId","integer")),
        def("list_loan_offers","Read offers and sanction terms for a permitted application, including expiry and acceptance status.","CUSTOMER","LOAN_READ",property("applicationId","integer")),
        def("list_loan_facilities","List loan facilities within the current customer's or officer's scope.","BOTH","LOAN_READ"),
        def("get_loan_details","Read a permitted loan's authoritative outstanding principal, interest and overdue amounts.","BOTH","LOAN_READ",property("facilityId","integer")),
        def("get_loan_schedule","Read the current repayment schedule for a permitted loan.","BOTH","LOAN_READ",property("facilityId","integer")),
        def("get_loan_prepayment_quote","Quote the exact early repayment/foreclosure amount, permitted modes and minimum partial payment. No money moves.","CUSTOMER","LOAN_READ",property("facilityId","integer")),
        def("list_credit_card_products","List available card products and disclosed limits, interest and billing terms.","CUSTOMER","CC_READ"),
        def("list_credit_card_applications","List the customer's card applications and approval status.","CUSTOMER","CC_READ"),
        def("list_credit_cards","List permitted credit cards with masked endings, balances, available credit and billing due flags.","BOTH","CC_READ"),
        def("get_credit_card","Read one permitted credit card's balances, status and agreed terms. Full card numbers are excluded.","BOTH","CC_READ",property("cardId","string")),
        def("list_credit_card_transactions","Read up to 100 transactions for a permitted card.","BOTH","CC_READ",property("cardId","string"),property("limit","integer")),
        def("list_credit_card_statements","List billing statements for a permitted credit card.","BOTH","CC_READ",property("cardId","string")),
        def("list_my_fixed_deposits","List the customer's fixed deposits, principal, interest rate, maturity date and status.","CUSTOMER","ACCOUNT_READ"),
        def("get_fixed_deposit","Read a customer's fixed deposit details. Early withdrawal is not supported.","CUSTOMER","ACCOUNT_READ",property("depositId","integer")),
        def("open_loan_application_form","Open a secure form for the customer to choose an eligible loan product and apply. Requires separate review; bank approval happens later.","CUSTOMER","LOAN_APPLY"),
        def("open_loan_offer_acceptance_form","Open a secure form to review an issued loan offer and choose owned repayment/disbursement accounts. Acceptance does not disburse funds.","CUSTOMER","LOAN_ACCEPT"),
        def("open_loan_repayment_form","Open a secure form for an authorized loan servicing user to apply a due repayment, with separate review of the account debit.","BOTH","LOAN_SERVICE"),
        def("open_loan_prepayment_form","Open a secure form for an authorized loan servicing user to apply partial early repayment or foreclosure. Refresh the quote before confirmation; do not invent a payoff amount.","BOTH","LOAN_SERVICE"),
        def("open_credit_card_application_form","Open a secure card application with actual terms, limit and account selection. Bank approval is independent.","CUSTOMER","CC_APPLY"),
        def("open_credit_card_control_form","Open a secure form to activate, freeze, unfreeze or close a card. Customer chat cannot permanently block a card.","CUSTOMER","CC_MANAGE"),
        def("open_credit_card_repayment_form","Open a secure card repayment form, debiting the linked account after separate PIN confirmation.","CUSTOMER","CC_REPAY"),
        def("open_credit_card_purchase_form","Open a secure simulated purchase form. No merchant payment occurs until separate PIN confirmation.","CUSTOMER","CC_SPEND"),
        def("open_credit_card_billing_form","Open a secure form to generate a due credit card statement on its next scheduled billing date.","CUSTOMER","CC_MANAGE"),
        def("open_fixed_deposit_form","Open a secure fixed deposit form with product terms and funding account selection. Opening debits principal only after separate confirmation. No early withdrawal.","CUSTOMER","TXN_POST")
    );
    Object invoke(String name,JsonNode args){
        return switch(name){
            case "list_my_product_offers"->offers();
            case "list_my_loan_applications"->loans.read(AssistantLoanPort.Read.APPLICATIONS,0);
            case "get_loan_application"->loans.read(AssistantLoanPort.Read.APPLICATION,id(args,"applicationId"));
            case "list_loan_offers"->loans.read(AssistantLoanPort.Read.OFFERS,id(args,"applicationId"));
            case "list_loan_facilities"->banking.facilities();
            case "get_loan_details"->loans.read(AssistantLoanPort.Read.FACILITY,id(args,"facilityId"));
            case "get_loan_schedule"->schedule(id(args,"facilityId"));
            case "get_loan_prepayment_quote"->loans.read(AssistantLoanPort.Read.PREPAYMENT_QUOTE,id(args,"facilityId"));
            case "list_credit_card_products"->cards.products();
            case "list_credit_card_applications"->cards.applications();
            case "list_credit_cards"->cards.cards().stream().map(this::maskedCard).toList();
            case "get_credit_card"->maskedCard(cards.card(text(args,"cardId",36)));
            case "list_credit_card_transactions"->cards.entries(text(args,"cardId",36),0,limit(args));
            case "list_credit_card_statements"->cards.statements(text(args,"cardId",36)).stream().limit(100).toList();
            case "list_my_fixed_deposits"->deposits.mine();
            case "get_fixed_deposit"->deposit(id(args,"depositId"));
            default->form(name);
        };
    }
    private Map<String,Object> maskedCard(Map<String,Object> card){
        var view=new LinkedHashMap<>(card);view.remove("CARD_REFERENCE");return view;
    }
    private Map<String,Object> deposit(long id){
        var view=new LinkedHashMap<>(deposits.get(id));view.remove("REQUEST_KEY");return view;
    }
    private List<Object> offers(){
        var user=CurrentActor.get();List<Object> result=new ArrayList<>();
        for(String cif:user.cifIds())for(JsonNode offer:json.valueToTree(products.customerOffers(user,cif,"WEB","INR").data())){
            if(result.size()==100)return result;
            var view=json.createObjectNode();view.put("cifId",cif);
            view.set("productId",offer.path("product").path("PRODUCT_ID"));
            view.set("productName",offer.path("product").path("PRODUCT_NAME"));
            view.set("productType",offer.path("product").path("PRODUCT_TYPE"));
            view.set("productVersionId",offer.path("version").path("PRODUCT_VERSION_ID"));
            for(String family:List.of("loan","term","interest"))if(offer.path("rules").has(family))view.set(family+"Terms",offer.path("rules").path(family));
            result.add(view);
        }
        return result;
    }
    private Object schedule(long id){
        var rows=loans.read(AssistantLoanPort.Read.SCHEDULE,id);var items=json.createArrayNode();
        for(int i=0;i<Math.min(100,rows.size());i++)items.add(rows.get(i));
        return Map.of("items",items,"totalInstallments",rows.size(),"truncated",rows.size()>100);
    }
    private static Map<String,Object> form(String name){
        String workflow=switch(name){
            case "open_loan_application_form"->"LOAN_APPLY";case "open_loan_offer_acceptance_form"->"LOAN_ACCEPT";
            case "open_loan_repayment_form"->"LOAN_REPAY";case "open_loan_prepayment_form"->"LOAN_PREPAY";
            case "open_credit_card_application_form"->"CARD_APPLY";case "open_credit_card_control_form"->"CARD_CONTROL";
            case "open_credit_card_repayment_form"->"CARD_REPAY";case "open_credit_card_purchase_form"->"CARD_PURCHASE";
            case "open_credit_card_billing_form"->"CARD_BILL";case "open_fixed_deposit_form"->"TERM_DEPOSIT_OPEN";
            default->throw new IllegalArgumentException("Unsupported product tool");};
        return Map.of("uiAction","BANKING_WORKFLOW","workflow",workflow,"formId",UUID.randomUUID().toString(),
                "summary","Open the secure banking form to choose the exact terms. Nothing is submitted or debited until you review and confirm.");
    }
}
