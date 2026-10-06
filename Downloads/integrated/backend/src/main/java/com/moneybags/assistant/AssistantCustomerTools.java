package com.moneybags.assistant;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import com.moneybags.integration.BankingAccess;
import com.moneybags.integration.CurrentActor;
import com.moneybags.statements.ApiModels.CreateRequest;
import com.moneybags.statements.ApiModels.RequestView;
import com.moneybags.statements.ApiModels.StatementView;
import com.moneybags.statements.StatementController;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Customer-facing projections over the existing payment and reporting workflows. */
@Service
public class AssistantCustomerTools {
    private final BusinessRepository db;
    private final BankingAccess access;
    private final StatementController statements;
    private final Set<String> internalBankCodes;

    public AssistantCustomerTools(BusinessRepository db, BankingAccess access, StatementController statements,
            @Value("${moneybags.internal-bank-codes:}") String bankCodes) {
        this.db=db; this.access=access; this.statements=statements;
        this.internalBankCodes=Arrays.stream(bankCodes.split(",")).map(String::trim).filter(s->!s.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<Map<String,Object>> beneficiaries() {
        customer();
        CurrentActor.require("PAYMENT_CREATE");
        return db.rows("SELECT B.BENEFICIARY_ID,B.DISPLAY_NAME,B.BANK_CODE,B.STATUS,B.CREATED_AT, " +
                "A.ACCOUNT_ID INTERNAL_ACCOUNT_ID,A.ACCOUNT_NUMBER INTERNAL_ACCOUNT_NUMBER " +
                "FROM MBX_BENEFICIARY B LEFT JOIN M04_BANK_ACCOUNT A ON A.ACCOUNT_NUMBER=B.ACCOUNT_TOKEN " +
                "WHERE B.OWNER_USER_ID=? ORDER BY B.CREATED_AT DESC",CurrentActor.get().userId())
            .stream().map(row->{
                Map<String,Object> view=new LinkedHashMap<>();
                for(String key:List.of("BENEFICIARY_ID","DISPLAY_NAME","BANK_CODE","STATUS","CREATED_AT"))view.put(key,row.get(key));
                boolean internal=row.get("INTERNAL_ACCOUNT_ID")!=null && internalBankCodes.contains(row.get("BANK_CODE"));
                view.put("TRANSFER_TYPE",internal?"INTERNAL":"EXTERNAL");
                if(internal)view.put("ACCOUNT_ENDING",last4(row.get("INTERNAL_ACCOUNT_NUMBER")));
                return view;
            }).toList();
    }

    private static String last4(Object value) {
        String number=String.valueOf(value);
        return number.substring(Math.max(0,number.length()-4));
    }

    public List<Map<String,Object>> payments(int limit) {
        customer();
        CurrentActor.require("PAYMENT_READ");
        return db.rows("SELECT PAYMENT_ID,SOURCE_ACCOUNT_ID,RAIL_CODE,AMOUNT,CURRENCY_CODE,STATUS,REASON_CODE,REQUESTED_EXECUTION_DATE,CREATED_AT,UPDATED_AT " +
                "FROM M06_PAYMENT_INSTRUCTION WHERE ORIGINATOR_ID=? AND PAYMENT_DIRECTION='OUTBOUND' " +
                "ORDER BY PAYMENT_ID DESC FETCH FIRST ? ROWS ONLY",CurrentActor.get().userId(),limit)
            .stream().filter(row->permitted(row)).map(this::paymentView).toList();
    }

    public Map<String,Object> payment(long id) {
        customer();
        CurrentActor.require("PAYMENT_READ");
        var row=db.one("SELECT PAYMENT_ID,SOURCE_ACCOUNT_ID,RAIL_CODE,AMOUNT,CURRENCY_CODE,STATUS,REASON_CODE,REQUESTED_EXECUTION_DATE,CREATED_AT,UPDATED_AT " +
                "FROM M06_PAYMENT_INSTRUCTION WHERE PAYMENT_ID=? AND ORIGINATOR_ID=? AND PAYMENT_DIRECTION='OUTBOUND'",
                id,CurrentActor.get().userId());
        access.account("PAYMENT_READ",((Number)row.get("SOURCE_ACCOUNT_ID")).longValue());
        return paymentView(row);
    }

    public List<StatementView> statements(long accountId,int limit) {
        customer();
        access.account("STATEMENT_READ",accountId);
        return statements.statements(CurrentActor.get(),accountId,limit);
    }

    public List<RequestView> statementRequests(long accountId,int limit) {
        customer();
        access.account("STATEMENT_READ",accountId);
        return statements.requests(CurrentActor.get(),accountId,limit);
    }

    public RequestView statementRequest(String id) {
        customer();
        return statements.request(CurrentActor.get(),id);
    }

    public Map<String,Object> statement(String id) {
        customer();
        StatementView view=statements.statement(CurrentActor.get(),id);
        var result=new LinkedHashMap<String,Object>();
        result.put("statementId",view.statementId());
        result.put("statementNumber",view.statementNumber());
        result.put("accountId",view.accountId());
        result.put("state",view.state());
        result.put("periodStart",view.periodStart());
        result.put("periodEnd",view.periodEnd());
        result.put("openingBalance",view.openingBalance());
        result.put("closingBalance",view.closingBalance());
        result.put("currency",view.currency());
        result.put("totalLines",view.lines().size());
        result.put("lines",view.lines().stream().limit(50).toList());
        result.put("linesTruncated",view.lines().size()>50);
        result.put("downloadPath","/api/v1/reporting/statements/"+id+"/download");
        result.put("downloadFormats",List.of("PDF","CSV","HTML"));
        return result;
    }

    public Map<String,Object> generateStatement(long accountId,LocalDate from,LocalDate through,String format) {
        customer();
        access.account("STATEMENT_READ",accountId);
        if(from.isBefore(LocalDate.of(1900,1,1)) || from.isAfter(through) ||
                through.isAfter(LocalDate.now(ZoneId.of("Asia/Kolkata"))) || from.plusYears(1).isBefore(through) ||
                !Set.of("PDF","CSV","HTML").contains(format))
            throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_STATEMENT_PERIOD",
                    "Choose a past period of at most one year and PDF, CSV, or HTML");
        var principal=CurrentActor.get();
        for(var row:db.rows("SELECT s.STATEMENT_ID FROM M10_STATEMENT_SNAPSHOT s JOIN M10_MASKING_PROFILE m " +
                "ON m.MASKING_PROFILE_ID=s.MASKING_PROFILE_ID WHERE s.ACCOUNT_ID=? AND s.STATE='ISSUED' " +
                "AND m.AUDIENCE_CODE='CUSTOMER' AND s.PERIOD_START_DATE=? AND s.PERIOD_END_DATE=? " +
                "ORDER BY s.ISSUED_AT DESC FETCH FIRST 1 ROW ONLY",accountId,from,through)) {
            String id=(String)row.get("STATEMENT_ID");
            statements.statement(principal,id);
            return Map.of("status","EXISTING","statementId",id,
                    "downloadPath","/api/v1/reporting/statements/"+id+"/download");
        }
        for(var row:db.rows("SELECT REQUEST_ID FROM M10_STATEMENT_REQUEST WHERE ACCOUNT_ID=? AND REQUESTER_ID=? " +
                "AND REQUESTER_TYPE='CUSTOMER' AND STATEMENT_TYPE='AD_HOC' AND PERIOD_START_DATE=? " +
                "AND PERIOD_END_DATE=? AND STATUS<>'CANCELLED' ORDER BY REQUESTED_AT DESC FETCH FIRST 1 ROW ONLY",
                accountId,principal.userId(),from,through)) {
            RequestView current=statements.request(principal,(String)row.get("REQUEST_ID"));
            if("AUTHORIZED".equals(current.status()))current=statements.issue(principal,current.requestId());
            return statementResult(current,"COMPLETED".equals(current.status())?"EXISTING":current.status());
        }
        RequestView request=statements.create(principal,UUID.randomUUID().toString(),
                new CreateRequest(accountId,null,"AD_HOC",from,through,"en-IN",format,"WEB",null,null,null,null,null)).getBody();
        RequestView issued=statements.issue(principal,request.requestId());
        return statementResult(issued,issued.status());
    }

    private Map<String,Object> statementResult(RequestView issued,String status) {
        var result=new LinkedHashMap<String,Object>();
        result.put("requestId",issued.requestId());
        result.put("status",status);
        result.put("statementId",issued.statementId());
        if(issued.failureCode()!=null)result.put("failureCode",issued.failureCode());
        if(issued.statementId()!=null)
            result.put("downloadPath","/api/v1/reporting/statements/"+issued.statementId()+"/download");
        return result;
    }

    private boolean permitted(Map<String,Object> row) {
        try { access.account("PAYMENT_READ",((Number)row.get("SOURCE_ACCOUNT_ID")).longValue()); return true; }
        catch(BusinessException denied) { return false; }
    }

    private Map<String,Object> paymentView(Map<String,Object> row) {
        var result=new LinkedHashMap<String,Object>();
        for(String key:List.of("PAYMENT_ID","SOURCE_ACCOUNT_ID","RAIL_CODE","AMOUNT","CURRENCY_CODE","STATUS","REASON_CODE","REQUESTED_EXECUTION_DATE","CREATED_AT","UPDATED_AT"))
            result.put(switch(key) {
                case "PAYMENT_ID" -> "paymentId";
                case "SOURCE_ACCOUNT_ID" -> "sourceAccountId";
                case "RAIL_CODE" -> "rail";
                case "AMOUNT" -> "amount";
                case "CURRENCY_CODE" -> "currency";
                case "STATUS" -> "status";
                case "REASON_CODE" -> "reasonCode";
                case "REQUESTED_EXECUTION_DATE" -> "requestedExecutionDate";
                case "CREATED_AT" -> "createdAt";
                default -> "updatedAt";
            },row.get(key));
        return result;
    }

    private static void customer() {
        if(!"CUSTOMER".equals(CurrentActor.get().userType()))
            throw new BusinessException(HttpStatus.FORBIDDEN,"CUSTOMER_ONLY","This tool belongs to a signed-in customer");
    }
}
