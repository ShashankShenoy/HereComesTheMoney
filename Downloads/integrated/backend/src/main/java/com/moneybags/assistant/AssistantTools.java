package com.moneybags.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneybags.cif.service.CifService;
import com.moneybags.common.api.BusinessException;
import com.moneybags.integration.BankingAccess;
import com.moneybags.integration.BankingController;
import com.moneybags.integration.BankingOperationsController;
import com.moneybags.integration.CurrentActor;
import com.moneybags.integration.CustomerHashService;
import com.moneybags.txn.core.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One allowlist and one dispatcher serve the chat agent and the MCP endpoint. */
@Service
public class AssistantTools {
    public record Definition(String name,String description,Map<String,Object> inputSchema,String audience,String permission) {
        public Map<String,Object> mcp() { return Map.of("name",name,"description",description,"inputSchema",inputSchema); }
        public Map<String,Object> model() { return Map.of("type","function","name",name,"description",description,"parameters",inputSchema,"strict",true); }
    }
    private static final List<Definition> DEFINITIONS=List.of(
        def("list_my_accounts","List accounts held by the signed-in customer, with masked account numbers.","CUSTOMER","ACCOUNT_READ"),
        def("get_my_account_overview","Show the signed-in customer's linked accounts, balances, and recent transactions. No account ID or extra key is needed.","CUSTOMER","ACCOUNT_READ"),
        def("list_my_recent_transactions","Show up to 20 recent transactions across the signed-in customer's linked accounts.","CUSTOMER","TXN_READ"),
        def("list_my_last_transactions","Show the last requested number of transactions across the signed-in customer's linked accounts (1 to 100).","CUSTOMER","TXN_READ",property("limit","integer")),
        def("list_scoped_accounts","List accounts within the signed-in bank officer's authorized scope.","EMPLOYEE","ACCOUNT_READ"),
        def("get_account_position","Read a permitted account's authoritative balance. Customer self-service uses the signed-in identity; officers need the account-holder access key.","BOTH","TXN_READ",property("accountId","integer")),
        def("list_recent_transactions","Read 1 to 100 transactions for an accessible account. Customer self-service uses the signed-in identity; officers need the account-holder access key.","BOTH","TXN_READ",property("accountId","integer"),property("limit","integer")),
        def("list_my_beneficiaries","List the signed-in customer's payment beneficiaries, including INTERNAL or EXTERNAL transfer type and masked internal account ending.","CUSTOMER","PAYMENT_CREATE"),
        def("open_add_beneficiary_form","Open the secure in-app form to register a new payment beneficiary. Use the recipient name the customer gave. The customer enters the account token and bank IFSC in the form, not in chat. Registration remains pending independent verification.","CUSTOMER","PAYMENT_CREATE",property("displayName","string")),
        def("draft_payment","Prepare a simulated outbound rail payment to an EXTERNAL beneficiary for exact confirmation. This does not credit a Moneybags account; use draft_internal_transfer for an INTERNAL beneficiary.","CUSTOMER","PAYMENT_CREATE",property("accountId","integer"),property("beneficiaryId","string"),property("rail","string"),property("amount","string")),
        def("draft_internal_transfer","Prepare a transfer to an ACTIVE INTERNAL beneficiary's Moneybags account. Requires exact customer confirmation before posting and crediting the recipient. No payment rail is needed.","CUSTOMER","TXN_POST",property("accountId","integer"),property("beneficiaryId","string"),property("amount","string")),
        def("list_my_payments","List the signed-in customer's last 1 to 100 initiated payments and their current status.","CUSTOMER","PAYMENT_READ",property("limit","integer")),
        def("get_my_payment_status","Get the current status of a payment initiated by the signed-in customer.","CUSTOMER","PAYMENT_READ",property("paymentId","integer")),
        def("list_my_statements","List up to 100 issued statement headers for one linked account.","CUSTOMER","STATEMENT_READ",property("accountId","integer"),property("limit","integer")),
        def("list_my_statement_requests","List up to 100 statement requests for one linked account, including pending or failed requests.","CUSTOMER","STATEMENT_READ",property("accountId","integer"),property("limit","integer")),
        def("get_my_statement_request","Check one of the signed-in customer's statement requests by ID.","CUSTOMER","STATEMENT_READ",property("requestId","string")),
        def("get_my_statement","Read an issued statement, with at most 50 lines in the tool response and an authenticated download path for all lines.","CUSTOMER","STATEMENT_READ",property("statementId","string")),
        def("generate_my_statement","Generate or reuse an issued statement for a linked account and past period of at most one year. Format is PDF, CSV, or HTML. This creates an immutable statement without a separate confirmation.","CUSTOMER","STATEMENT_READ",property("accountId","integer"),property("periodStart","string"),property("periodEnd","string"),property("format","string")),
        def("search_customers","Find customers within the bank officer's authorized scope. Returns minimal profile fields.","EMPLOYEE","CIF_READ",property("query","string")),
        def("list_pending_beneficiaries","List pending beneficiaries for an authorized independent checker.","EMPLOYEE","BENEFICIARY_VERIFY"),
        def("draft_beneficiary_verification","Prepare verification of one pending beneficiary for exact confirmation in the UI.","EMPLOYEE","BENEFICIARY_VERIFY",property("beneficiaryId","string"))
    );
    private final BankingController banking;
    private final BankingOperationsController operations;
    private final BankingAccess access;
    private final CustomerHashService hashes;
    private final LedgerService ledger;
    private final CifService cif;
    private final AssistantIntentService intents;
    private final AssistantCustomerTools customerTools;
    public AssistantTools(BankingController banking,BankingOperationsController operations,BankingAccess access,
            CustomerHashService hashes,LedgerService ledger,CifService cif,AssistantIntentService intents,
            AssistantCustomerTools customerTools) {
        this.banking=banking;this.operations=operations;this.access=access;this.hashes=hashes;
        this.ledger=ledger;this.cif=cif;this.intents=intents;this.customerTools=customerTools;
    }

    public List<Definition> available() {
        var user=CurrentActor.get();
        if(!"CUSTOMER".equals(user.userType()) && !"EMPLOYEE".equals(user.userType()))return List.of();
        return DEFINITIONS.stream().filter(d->("BOTH".equals(d.audience()) || d.audience().equals(user.userType()))
                && user.permissions().contains(d.permission())).toList();
    }
    public Object invoke(String name,JsonNode args,String customerHash) {
        Definition definition=available().stream().filter(d->d.name().equals(name)).findFirst()
                .orElseThrow(()->new BusinessException(HttpStatus.FORBIDDEN,"TOOL_UNAVAILABLE","This tool is outside your access"));
        validate(args,definition);
        try { return switch(name) {
            case "list_my_accounts","list_scoped_accounts" -> accounts();
            case "get_my_account_overview" -> banking.myDashboard();
            case "list_my_recent_transactions" -> myRecentTransactions(20);
            case "list_my_last_transactions" -> myRecentTransactions(limit(args));
            case "get_account_position" -> position(id(args,"accountId"),customerHash);
            case "list_recent_transactions" -> transactions(id(args,"accountId"),limit(args),customerHash);
            case "list_my_beneficiaries" -> customerTools.beneficiaries();
            case "open_add_beneficiary_form" -> beneficiaryForm(text(args,"displayName",100));
            case "draft_payment" -> intents.draftPayment(id(args,"accountId"),text(args,"beneficiaryId",36),text(args,"rail",10),amount(args));
            case "draft_internal_transfer" -> intents.draftInternalTransfer(id(args,"accountId"),text(args,"beneficiaryId",36),amount(args));
            case "list_my_payments" -> customerTools.payments(limit(args));
            case "get_my_payment_status" -> customerTools.payment(id(args,"paymentId"));
            case "list_my_statements" -> customerTools.statements(id(args,"accountId"),limit(args));
            case "list_my_statement_requests" -> customerTools.statementRequests(id(args,"accountId"),limit(args));
            case "get_my_statement_request" -> customerTools.statementRequest(text(args,"requestId",36));
            case "get_my_statement" -> customerTools.statement(text(args,"statementId",36));
            case "generate_my_statement" -> customerTools.generateStatement(id(args,"accountId"),date(args,"periodStart"),date(args,"periodEnd"),text(args,"format",8));
            case "search_customers" -> customers(text(args,"query",120));
            case "list_pending_beneficiaries" -> operations.pending();
            case "draft_beneficiary_verification" -> intents.draftBeneficiaryVerification(text(args,"beneficiaryId",36));
            default -> throw new BusinessException(HttpStatus.BAD_REQUEST,"UNKNOWN_TOOL","Unknown tool");
        }; }
        catch(com.moneybags.statements.ApiException e) {
            throw new BusinessException(e.status,e.code,e.getMessage());
        }
    }
    private List<Map<String,Object>> accounts() {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var a:banking.accounts()) {
            String number=String.valueOf(a.get("ACCOUNT_NUMBER"));
            var row=new LinkedHashMap<String,Object>();
            row.put("accountId",a.get("ACCOUNT_ID"));
            row.put("accountEnding",number.substring(Math.max(0,number.length()-4)));
            row.put("status",a.get("ACCOUNT_STATUS"));
            if("EMPLOYEE".equals(CurrentActor.get().userType()))row.put("branch",a.get("BRANCH_CODE"));
            result.add(row);
        }
        return result;
    }
    private Object position(long accountId,String hash) {
        access.account("TXN_READ",accountId);
        hashes.require(accountId,hash);
        hashes.audit("ASSISTANT_POSITION_VIEWED","ACCOUNT",Long.toString(accountId),hashes.proofLabel());
        return ledger.position(accountId);
    }
    private List<Map<String,Object>> myRecentTransactions(int limit) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var account:banking.accounts()) {
            long accountId=((Number)account.get("ACCOUNT_ID")).longValue();
            access.account("TXN_READ",accountId);
            String number=String.valueOf(account.get("ACCOUNT_NUMBER"));
            String ending=number.substring(Math.max(0,number.length()-4));
            for(var txn:banking.transactions(accountId,null).stream().limit(limit).toList()) {
                var row=new LinkedHashMap<String,Object>(txn);
                row.put("accountEnding",ending);
                result.add(row);
            }
        }
        result.sort((a,b)->Long.compare(((Number)b.get("transactionId")).longValue(),((Number)a.get("transactionId")).longValue()));
        return result.stream().limit(limit).toList();
    }
    private Object transactions(long accountId,int limit,String hash) {
        access.account("TXN_READ",accountId);
        hashes.require(accountId,hash);
        return banking.transactions(accountId,hash).stream().limit(limit).toList();
    }
    private Object customers(String query) {
        if(query.isBlank())invalid("Enter a customer name or CIF number");
        return cif.list(CurrentActor.get(),query,null).stream().limit(20).map(c->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("cifId",c.get("CIF_ID"));row.put("cifNumber",c.get("CIF_NUMBER"));
            row.put("name",c.get("LEGAL_NAME"));row.put("branch",c.get("HOME_BRANCH_REF"));
            row.put("status",c.get("STATUS"));return row;
        }).toList();
    }
    private static Map<String,Object> beneficiaryForm(String displayName) {
        if(displayName.chars().anyMatch(Character::isISOControl))invalid("Invalid displayName");
        return Map.of("uiAction","ADD_BENEFICIARY","formId",UUID.randomUUID().toString(),
                "displayName",displayName,
                "message","A secure registration form is ready. The customer must enter the account number or UPI ID and bank IFSC there. Registration stays pending until an independent bank officer verifies it.");
    }
    private static void validate(JsonNode args,Definition def) {
        if(args==null || !args.isObject())invalid("Tool arguments must be an object");
        @SuppressWarnings("unchecked") Map<String,Object> schema=def.inputSchema();
        @SuppressWarnings("unchecked") Map<String,Object> fields=(Map<String,Object>)schema.get("properties");
        args.fieldNames().forEachRemaining(key->{if(!fields.containsKey(key))invalid("Unexpected tool argument");});
        for(String key:fields.keySet())if(!args.hasNonNull(key))invalid("Missing tool argument: "+key);
    }
    private static long id(JsonNode args,String key) {
        JsonNode value=args.get(key);if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<=0)invalid("Invalid "+key);
        return value.longValue();
    }
    private static int limit(JsonNode args) {
        JsonNode value=args.get("limit");if(!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue()<1 || value.intValue()>100)invalid("Limit must be 1 to 100");
        return value.intValue();
    }
    private static LocalDate date(JsonNode args,String key) {
        String value=text(args,key,10);
        try { if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();return LocalDate.parse(value); }
        catch(java.time.DateTimeException | IllegalArgumentException e) { invalid("Invalid "+key+"; use YYYY-MM-DD");return null; }
    }
    private static String text(JsonNode args,String key,int max) {
        JsonNode value=args.get(key);if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>max)invalid("Invalid "+key);
        return value.asText().trim();
    }
    private static BigDecimal amount(JsonNode args) {
        String value=text(args,"amount",32);
        if(!value.matches("[0-9]{1,16}(\\.[0-9]{1,2})?"))invalid("Amount must be positive INR with at most two decimal places");
        return new BigDecimal(value);
    }
    private static void invalid(String message) { throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENT",message); }
    private static Map<String,Object> property(String name,String type) { return Map.of("name",name,"type",type); }
    @SafeVarargs private static Definition def(String name,String description,String audience,String permission,Map<String,Object>... properties) {
        Map<String,Object> fields=new LinkedHashMap<>();List<String> required=new ArrayList<>();
        for(var p:properties) {String key=(String)p.get("name");required.add(key);fields.put(key,Map.of("type",p.get("type")));}
        return new Definition(name,description,Map.of("type","object","properties",fields,"required",required,"additionalProperties",false),audience,permission);
    }
}
