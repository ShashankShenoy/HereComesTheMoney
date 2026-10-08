package com.moneybags.account.domain;

import com.moneybags.account.api.ApiException;
import com.moneybags.account.api.Models.*;
import com.moneybags.account.db.*;
import com.moneybags.account.integration.ModuleClients;
import com.moneybags.account.security.Actor;
import com.moneybags.integration.CurrentActor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Implements account ownership and workflow rules; peer decisions precede local transactions. */
@Service
public class AccountService {
    private final AccountStore accounts;
    private final WorkflowStore workflows;
    private final CustomerRequestStore customerRequests;
    private final EventStore events;
    private final ModuleClients peers;
    private final Actor actor;
    private final TransactionTemplate tx;
    private final com.moneybags.integration.AccountOverrideApprovals approvals;
    private final boolean demoCustomerOpening;

    public AccountService(AccountStore accounts, WorkflowStore workflows, CustomerRequestStore customerRequests, EventStore events,
                          ModuleClients peers, Actor actor, TransactionTemplate tx,com.moneybags.integration.AccountOverrideApprovals approvals,
                          @Value("${moneybags.customer-signup.enabled:false}") boolean demoCustomerOpening) {
        this.accounts = accounts; this.workflows = workflows; this.customerRequests = customerRequests; this.events = events;
        this.peers = peers; this.actor = actor; this.tx = tx;this.approvals=approvals;this.demoCustomerOpening=demoCustomerOpening;
    }

    /** Returns an account only to assigned customers or authorized account officers. */
    public AccountView get(long id) {
        AccountView view = accounts.account(id, false);
        requireRead(view);
        return view;
    }
    /** Resolves an account number only for its parties or a staff account officer. */
    public AccountView byNumber(String number) {
        AccountView view = accounts.byNumber(number)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));
        requireRead(view);
        return view;
    }
    /** Gives signed peer services the account identity and control version they need. */
    public Map<String, Object> peerContext(long id) {
        actor.require("ACCOUNT_PEER_READ");
        AccountView a = accounts.account(id, false);
        return Map.of("accountId", Long.toString(a.id()), "accountNumber", a.number(),
                "primaryCifId", a.primaryCifId(), "productId", Long.toString(a.productId()),
                "productVersionId", Long.toString(a.productVersionId()), "currencyCode", "INR",
                "lifecycleStatus", a.lifecycleStatus(), "accountStatus", a.accountStatus(),
                "operationMode", a.operationMode(), "controlVersion", Long.toString(a.financialControlVersion()));
    }
    /** Gives a peer service only active party roles, without nominee PII. */
    public List<Map<String, Object>> peerParties(long id) {
        actor.require("ACCOUNT_PEER_READ");
        accounts.account(id, false);
        return accounts.activeParties(id);
    }
    /** Lists a customer's accounts; staff must still obtain an IAM decision. */
    public Page<AccountView> byCif(String cif, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) throw bad("Invalid pagination");

        List<AccountView> items = accounts.byCif(cif, limit, offset).stream().filter(v -> {
            try {requireRead(v);return true;} catch (ApiException | com.moneybags.common.api.BusinessException denied) {return false;}
        }).toList();
        return new Page<>(items, limit, offset);
    }
    /** Lists one of the fixed account child collections after a read-access check. */
    public List<Map<String, Object>> children(long id, String kind) {
        requireRead(accounts.account(id, false));
        if ((kind.equals("opening-evidence") || kind.equals("control-sync")) && !actor.has("ACCOUNT_OFFICER"))
            throw forbidden();
        if (kind.equals("nominees") && !actor.has("ACCOUNT_OFFICER")
                && !accounts.activePrimary(id, actor.cifId())) throw forbidden();
        return accounts.children(id, kind);
    }
    /** Opens a pending account using fresh CIF, product, and IAM decisions. */
    public AccountView open(OpenAccount command) {
        actor.require("ACCOUNT_OFFICER");
        return openChecked(command,false);
    }
    /** Demo self-service uses the signed-in customer's linked, verified CIF. */
    public AccountView openSelf(SelfOpenAccount request) {
        requireDemoCustomer(request.cifId());
        var customer=peers.customer(request.cifId(),"");
        if(Boolean.TRUE.equals(customer.minor()))throw conflict("A minor account needs a guardian and bank officer");
        return openChecked(new OpenAccount(request.requestId(),request.cifId(),request.productId(),
                request.productVersionId(),customer.homeBranch(),"SELF_OPERATED",List.of()),true);
    }
    private void requireDemoCustomer(String cif){
        if(!demoCustomerOpening||!"CUSTOMER".equals(CurrentActor.get().userType())||!CurrentActor.get().cifIds().contains(cif))
            throw forbidden();
    }
    private AccountView openChecked(OpenAccount command,boolean self){
        Optional<AccountView> old = accounts.byOpenRequest(command.requestId());
        if (old.isPresent()) {
            AccountView a = old.get();
            if(self&&!actor.id().equals(accounts.createdBy(a.id())))throw forbidden();
            if (!a.primaryCifId().equals(command.primaryCifId()) || a.productVersionId() != command.productVersionId())
                throw conflict("Request ID belongs to a different opening");
            requireRead(a);
            if(!a.branchCode().equals(command.branchCode()) || !a.operationMode().equals(command.operationMode()))throw conflict("Request ID belongs to a different opening");
            return a;
        }
        ModuleClients.CustomerDecision customer = peers.customer(command.primaryCifId(), command.branchCode());
        ModuleClients.ProductDecision product = peers.product(command.productId(), command.productVersionId(), command.branchCode(), command.primaryCifId());
        ModuleClients.IamDecision iam = self
                ? new ModuleClients.IamDecision("DEMO_CUSTOMER_SELF_OPEN",true)
                : peers.opening(command.branchCode(),command.primaryCifId(),product.productType());
        if(!iam.allowed())throw forbidden();
        validateOpening(command, customer, product);
        if (Boolean.TRUE.equals(customer.minor()) && nullSafe(command.additionalParties()).stream()
                .noneMatch(p -> "GUARDIAN".equals(p.role())))
            throw bad("Minor account requires a guardian party");
        if ("JOINTLY".equals(command.operationMode()) && nullSafe(command.additionalParties()).stream()
                .noneMatch(p -> "JOINT_HOLDER".equals(p.role())))
            throw bad("Joint operation requires a joint holder");
        for (PartyInput party : nullSafe(command.additionalParties())) {
            if (party.role().equals("PRIMARY_HOLDER")) throw bad("Primary holder is created from primaryCifId");
            if (!Set.of("JOINT_HOLDER", "AUTHORIZED_SIGNATORY", "GUARDIAN").contains(party.role())) throw bad("Invalid party role");
            if (party.role().equals("GUARDIAN") && party.cifId().equals(command.primaryCifId())) throw bad("Minor cannot be own guardian");
            if (party.role().equals("GUARDIAN") && !"GUARDIAN".equals(party.operatingInstruction()))
                throw bad("Guardian role needs guardian operating instruction");
            ModuleClients.CustomerDecision p = peers.party(party.cifId(), command.branchCode());
            if (!"ACTIVE".equals(p.status())) throw conflict("Additional party is not active in CIF");
            if (party.role().equals("GUARDIAN") && !Boolean.FALSE.equals(p.minor()))
                throw conflict("Guardian must be an adult in CIF");
        }
        String number = accounts.nextNumber(command.branchCode(), product.productType());
        String correlation = UUID.randomUUID().toString();
        return tx.execute(status -> {
            long id = accounts.insertAccount(command, number, actor.id(), Boolean.TRUE.equals(customer.minor()));
            accounts.addParty(id, new PartyInput(command.primaryCifId(), "PRIMARY_HOLDER",
                    Boolean.TRUE.equals(customer.minor()) ? "GUARDIAN" : "SELF"), actor.id());
            for (PartyInput party : nullSafe(command.additionalParties())) accounts.addParty(id, party, actor.id());
            accounts.openingEvidence(id, command.requestId() + ":open", customer.decisionRef(),
                    product.decisionRef(), iam.decisionRef(), command.productVersionId(),
                    product.ruleSetHash(), "PASS", null);
            accounts.statusHistory(id, "LIFECYCLE", null, "PENDING_OPEN", "ACCOUNT_OPENED", null,
                    actor.id(), correlation);
            events.outbox(id, "ACCOUNT_OPENED", correlation, Map.of("accountId", id,
                    "accountNumber", number, "primaryCifId", command.primaryCifId(),
                    "productVersionId", command.productVersionId(), "status", "PENDING_OPEN"));
            events.audit(id, "ACCOUNT_OPEN", actor.id(), "SUCCESS", correlation, Map.of("requestId", command.requestId()));
            return accounts.account(id, false);
        });
    }
    /** Requests Module 5 to open the posting fence; activation completes on its ack. */
    public CommandResult activate(long id, String requestId) {
        actor.require("ACCOUNT_OFFICER");
        return activateChecked(id,requestId,false);
    }
    public CommandResult activateSelf(long id,String requestId){
        var account=accounts.account(id,false);
        requireDemoCustomer(account.primaryCifId());
        if(!actor.id().equals(accounts.createdBy(id))||!"SELF_OPERATED".equals(account.operationMode()))throw forbidden();
        return activateChecked(id,requestId,true);
    }
    private CommandResult activateChecked(long id,String requestId,boolean self){
        AccountView original = accounts.account(id, false);
        if (!"PENDING_OPEN".equals(original.lifecycleStatus())) throw conflict("Only pending accounts can activate");
        if (workflows.controlByRequest(requestId).isPresent()) {
            Map<String, Object> prior = workflows.controlByRequest(requestId).orElseThrow();
            if (((Number) prior.get("ACCOUNT_ID")).longValue() != id) throw conflict("Request ID belongs to another account");
            return new CommandResult(id, String.valueOf(prior.get("SYNC_STATUS")), requestId,
                    ((Number) prior.get("CONTROL_VERSION")).longValue());
        }
        ModuleClients.CustomerDecision customer = peers.customer(original.primaryCifId(), original.branchCode());
        ModuleClients.ProductDecision product = peers.product(original.productId(), original.productVersionId(), original.branchCode(), original.primaryCifId());
        ModuleClients.IamDecision iam = self
                ? new ModuleClients.IamDecision("DEMO_CUSTOMER_SELF_ACTIVATE",true)
                : authorize("ACCOUNT_ACTIVATE", id);
        validateOpening(new OpenAccount(requestId, original.primaryCifId(), original.productId(),
                original.productVersionId(), original.branchCode(), original.operationMode(), List.of()), customer, product);
        if (!accounts.activePrimary(id, original.primaryCifId())) throw conflict("Active primary holder is missing");
        if (Boolean.TRUE.equals(customer.minor()) && !accounts.hasActiveRole(id, "GUARDIAN")) throw conflict("Guardian authority is missing");
        if (Boolean.TRUE.equals(customer.minor())) {
            for (Map<String, Object> party : accounts.activeParties(id)) {
                if ("GUARDIAN".equals(party.get("PARTY_ROLE"))) {
                    ModuleClients.CustomerDecision guardian = peers.party((String) party.get("CIF_ID"), original.branchCode());
                    if (!"ACTIVE".equals(guardian.status()) || !Boolean.FALSE.equals(guardian.minor()))
                        throw conflict("Guardian is no longer eligible in CIF");
                }
            }
        }
        if ("JOINTLY".equals(original.operationMode()) && !accounts.hasActiveRole(id, "JOINT_HOLDER"))
            throw conflict("Joint holder is missing");
        if (product.minimumOpeningBalance() != null && product.minimumOpeningBalance().signum() > 0
                && !peers.openingFunding(id, product.minimumOpeningBalance()).cleared())
            throw conflict("Opening funding has not cleared");
        String eventId = UUID.randomUUID().toString();
        return tx.execute(status -> {
            AccountView locked = accounts.account(id, true);
            if (locked.rowVersion() != original.rowVersion()) throw conflict("Account changed during activation check");
            long version = workflows.insertControl(id, null, requestId, "APPLY", "LIFECYCLE", null, eventId);
            accounts.openingEvidence(id, requestId + ":activate", customer.decisionRef(),
                    product.decisionRef(), iam.decisionRef(), original.productVersionId(),
                    product.ruleSetHash(), "PASS", null);
            events.controlOutbox(eventId, id, requestId, Map.of("accountId", id, "controlVersion", version,
                    "targetLifecycle", "ACTIVE", "action", "APPLY", "controlType", "LIFECYCLE"));
            events.audit(id, "ACCOUNT_ACTIVATION_REQUEST", actor.id(), "SUCCESS", requestId, Map.of("controlVersion", version));
            return new CommandResult(id, "PENDING_MODULE5_ACK", requestId, version);
        });
    }
    /** Cancels an unfunded or fully reversed opening and retires its account number. */
    public void cancelPending(long id, ReasonCommand command) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_OPEN_CANCEL", id);
        AccountView before = accounts.account(id, false);
        if (!"PENDING_OPEN".equals(before.lifecycleStatus())) throw conflict("Only pending opening can be cancelled");
        if (workflows.latestLifecycleControl(id).filter(c -> "PENDING".equals(c.get("SYNC_STATUS"))).isPresent())
            throw conflict("Activation control is still pending with Module 5");
        ModuleClients.Clearance funding = peers.transactionClearance(id, before.financialControlVersion());
        if (!funding.cleared() || funding.reference() == null || funding.reference().isBlank())
            throw conflict("Opening funding or holds must be reversed first");
        tx.executeWithoutResult(status -> {
            AccountView locked = accounts.account(id, true);
            if (locked.rowVersion() != before.rowVersion()) throw conflict("Account changed during cancellation check");
            accounts.lifecycle(id, "CANCELLED", "CANCELLED", actor.id());
            accounts.statusHistory(id, "LIFECYCLE", "PENDING_OPEN", "CANCELLED", command.reasonCode(),
                    command.remarks(), actor.id(), command.requestId());
            events.outbox(id, "ACCOUNT_OPENING_CANCELLED", command.requestId(),
                    Map.of("accountId", id, "clearanceRef", funding.reference()));
            events.audit(id, "ACCOUNT_OPEN_CANCEL", actor.id(), "SUCCESS", command.requestId(),
                    Map.of("requestId", command.requestId()));
        });
    }
    /** Adds a non-primary account party after CIF and IAM checks. */
    public void addParty(long id, PartyInput party) {
        actor.require("ACCOUNT_OFFICER");
        AccountView account = accounts.account(id, false);
        requireMutable(account);
        authorize("ACCOUNT_PARTY_CHANGE", id);
        if ("PRIMARY_HOLDER".equals(party.role())) throw bad("Primary holder changes require a separate transfer workflow");
        if (!Set.of("JOINT_HOLDER", "AUTHORIZED_SIGNATORY", "GUARDIAN").contains(party.role())) throw bad("Invalid party role");
        if (!Set.of("SELF", "ANYONE", "JOINTLY", "GUARDIAN").contains(party.operatingInstruction())) throw bad("Invalid operating instruction");
        if ("GUARDIAN".equals(party.role()) && !"GUARDIAN".equals(party.operatingInstruction()))
            throw bad("Guardian role needs guardian operating instruction");
        ModuleClients.CustomerDecision partyDecision = peers.party(party.cifId(), account.branchCode());
        if (!"ACTIVE".equals(partyDecision.status()) ||
                ("GUARDIAN".equals(party.role()) && !Boolean.FALSE.equals(partyDecision.minor())))
            throw conflict("CIF party is not eligible");
        tx.executeWithoutResult(status -> {
            requireMutable(accounts.account(id, true));
            accounts.addParty(id, party, actor.id());
            events.outbox(id, "ACCOUNT_PARTY_ADDED", UUID.randomUUID().toString(),
                    Map.of("accountId", id, "cifId", party.cifId(), "role", party.role()));
        });
    }
    /** Ends a non-primary assignment while preserving party history. */
    public void endParty(long id, long partyId) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_PARTY_CHANGE", id);
        tx.executeWithoutResult(status -> {
            AccountView account = accounts.account(id, true);
            requireMutable(account);
            if ("GUARDIAN".equals(accounts.partyRole(id, partyId).orElse(null)) &&
                    "GUARDIAN_OPERATED".equals(account.operationMode()))
                throw conflict("Guardian authority requires a majority review before removal");
            if ("JOINT_HOLDER".equals(accounts.partyRole(id, partyId).orElse(null)) &&
                    "JOINTLY".equals(account.operationMode()) && accounts.activeRoleCount(id, "JOINT_HOLDER") <= 1)
                throw conflict("Joint operation requires a joint holder");
            if (accounts.endParty(id, partyId) == 0) throw conflict("Active non-primary party not found");
            events.outbox(id, "ACCOUNT_PARTY_ENDED", UUID.randomUUID().toString(),
                    Map.of("accountId", id, "partyId", partyId));
        });
    }
    /** Replaces nominations as one unit, checking that active shares total exactly 100. */
    public void replaceNominees(long id, ReplaceNominees command) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_NOMINEE_CHANGE", id);
        replaceNomineesChecked(id, command);
    }
    public void replaceNomineesSelf(long id, ReplaceNominees command) {
        AccountView a = accounts.account(id, false);
        requireDemoCustomer(a.primaryCifId());
        if (!"SELF_OPERATED".equals(a.operationMode()) || !accounts.activePrimary(id, a.primaryCifId()) ||
                accounts.activeParties(id).stream().anyMatch(p -> !"PRIMARY_HOLDER".equals(p.get("PARTY_ROLE"))))
            throw forbidden();
        replaceNomineesChecked(id, command);
    }
    private void replaceNomineesChecked(long id, ReplaceNominees command) {
        AccountView a = accounts.account(id, false);
        requireMutable(a);
        BigDecimal total = command.nominees().stream().map(NomineeInput::sharePercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100.00")) != 0) throw bad("Nominee shares must total 100");
        for (NomineeInput n : command.nominees()) {
            if (n.dateOfBirth() != null) java.time.LocalDate.parse(n.dateOfBirth());
        }
        tx.executeWithoutResult(status -> {
            requireMutable(accounts.account(id, true));
            if (events.commandRecorded(id, "ACCOUNT_NOMINEES_CHANGED", command.requestId())) return;
            accounts.replaceNominees(id, command.nominees(), actor.id());
            events.outbox(id, "ACCOUNT_NOMINEES_CHANGED", command.requestId(),
                    Map.of("accountId", id, "nomineeCount", command.nominees().size()));
            events.audit(id, "NOMINEES_REPLACED", actor.id(), "SUCCESS", command.requestId(),
                    Map.of("nomineeCount", command.nominees().size()));
        });
    }
    /** Creates a pending control; the restriction becomes active only on Module 5 ack. */
    public CommandResult restrict(long id, RestrictionCommand command) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_RESTRICT", id);
        return restrictChecked(id, command);
    }
    /** A sole account holder can secure an active account immediately; bank staff release the control. */
    public CommandResult restrictSelf(long id, SelfSafetyBlock request) {
        AccountView account = accounts.account(id, false);
        requireSolePrimaryCustomer(account);
        if (!Set.of("FREEZE", "DEBIT_BLOCK").contains(request.type()))
            throw bad("Choose a temporary freeze or debit block");
        return restrictChecked(id, new RestrictionCommand(request.requestId(), request.type(), null,
                "CUSTOMER_SAFETY", request.details(), "CUSTOMER", request.requestId(), null));
    }
    private CommandResult restrictChecked(long id, RestrictionCommand command) {
        validateRestriction(command);
        Optional<Map<String, Object>> existing = workflows.restrictionByRequest(command.requestId());
        if (existing.isPresent()) {
            if (((Number) existing.get().get("ACCOUNT_ID")).longValue() != id) throw conflict("Request ID belongs to another account");
            return new CommandResult(id, String.valueOf(existing.get().get("RESTRICTION_STATUS")),
                    command.requestId(), null);
        }
        String eventId = UUID.randomUUID().toString();
        return tx.execute(status -> {
            AccountView a = accounts.account(id, true);
            if (!"ACTIVE".equals(a.lifecycleStatus())) throw conflict("Account is not active");
            long restrictionId = workflows.insertRestriction(id, command, actor.id());
            long version = workflows.insertControl(id, restrictionId, command.requestId(), "APPLY",
                    command.type(), command.amount(), eventId);
            events.controlOutbox(eventId, id, command.requestId(), controlPayload(id, version,
                    restrictionId, "APPLY", command.type(), command.amount()));
            events.audit(id, "RESTRICTION_REQUESTED", actor.id(), "SUCCESS", command.requestId(),
                    Map.of("restrictionId", restrictionId, "type", command.type()));
            return new CommandResult(id, "PENDING_MODULE5_ACK", command.requestId(), version);
        });
    }
    /** Requests removal while keeping the current restriction effective until ack. */
    public CommandResult removeRestriction(long id, long restrictionId, String requestId) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_RESTRICT", id);
        Optional<Map<String, Object>> old = workflows.controlByRequest(requestId);
        if (old.isPresent()) {
            if (((Number) old.get().get("ACCOUNT_ID")).longValue() != id) throw conflict("Request ID belongs to another account");
            return new CommandResult(id, String.valueOf(old.get().get("SYNC_STATUS")),
                    requestId, ((Number) old.get().get("CONTROL_VERSION")).longValue());
        }
        String eventId = UUID.randomUUID().toString();
        return tx.execute(status -> {
            accounts.account(id, true);
            Map<String, Object> restriction = workflows.restriction(restrictionId);
            if (((Number) restriction.get("ACCOUNT_ID")).longValue() != id) throw conflict("Restriction belongs to another account");
            if (!"ACTIVE".equals(restriction.get("RESTRICTION_STATUS"))) throw conflict("Restriction is not active");
            workflows.pendingRemove(restrictionId);
            String type = (String) restriction.get("RESTRICTION_TYPE");
            BigDecimal amount = (BigDecimal) restriction.get("RESTRICTION_AMOUNT");
            long version = workflows.insertControl(id, restrictionId, requestId, "REMOVE", type, amount, eventId);
            events.controlOutbox(eventId, id, requestId, controlPayload(id, version, restrictionId,
                    "REMOVE", type, amount));
            return new CommandResult(id, "PENDING_MODULE5_ACK", requestId, version);
        });
    }
    /** Saves a limit only after Product Master confirms the requested amount. */
    public void addLimit(long id, LimitCommand command) {
        actor.require("ACCOUNT_OFFICER");
        AccountView a = accounts.account(id, false); authorize("ACCOUNT_LIMIT_CHANGE", id);
        requireMutable(a);
        if (command.effectiveTo() != null && !java.time.LocalDate.parse(command.effectiveTo())
                .isAfter(java.time.LocalDate.parse(command.effectiveFrom())))
            throw bad("Limit end date must follow start date");
        if (command.overridePolicyId() != null && (command.approvedByUserId() == null
                || command.approvedByUserId().equals(actor.id()))) throw bad("Separate approver is required");
        if (!peers.limit(a.productVersionId(), command.productLimitRuleId(), command.overridePolicyId(),
                command.operationCode(), command.periodCode(), command.amount()).allowed()) throw conflict("Product limit rule denies this amount");
        tx.executeWithoutResult(status -> {
            requireMutable(accounts.account(id, true));
            if(command.overridePolicyId()!=null)approvals.consume(id,"LIMIT",command.requestId(),command);
            if (workflows.limitOverlap(id, command)) throw conflict("An active limit already covers this window");
            workflows.insertLimit(id, command, actor.id());
            events.outbox(id, "ACCOUNT_LIMIT_CHANGED", command.requestId(),
                    Map.of("accountId", id, "limit", command));
        });
    }
    /** Records a customer preference; it has no effect on the enforced account limit. */
    public Map<String,Object> requestLimitSelf(long id, SelfLimitRequest request) {
        AccountView account = accounts.account(id, false);
        requireCustomerAccount(account);
        if (!"ACTIVE".equals(account.lifecycleStatus())) throw conflict("Account must be active");
        if (!"INTERNAL_TRANSFER".equals(request.operationCode()) ||
                !Set.of("PER_TRANSACTION","DAY").contains(request.periodCode()))
            throw bad("Invalid operation or period");
        return insertCustomerRequest(id,request.requestId(),"LIMIT_CHANGE",request.operationCode(),
                request.periodCode(),request.requestedAmount(),request.reason());
    }
    /** An authorized party can report an issue for bank follow-up. */
    public Map<String,Object> reportIssueSelf(long id, SelfIssueRequest request) {
        AccountView account = accounts.account(id, false);
        requireCustomerAccount(account);
        if (Set.of("CANCELLED","CLOSED").contains(account.lifecycleStatus()))
            throw conflict("This account is no longer open");
        return insertCustomerRequest(id,request.requestId(),"ISSUE",null,null,null,request.details());
    }
    private Map<String,Object> insertCustomerRequest(long id,String requestId,String type,
            String operation,String period,BigDecimal amount,String details) {
        var old=customerRequests.byRequest(requestId);
        if(old.isPresent()) {
            var prior=old.get();
            if(((Number)prior.get("ACCOUNT_ID")).longValue()!=id || !actor.id().equals(prior.get("REQUESTED_BY_USER_ID")) ||
                    !type.equals(prior.get("REQUEST_TYPE")) || !Objects.equals(operation,prior.get("OPERATION_CODE")) ||
                    !Objects.equals(period,prior.get("PERIOD_CODE")) ||
                    !(amount==null ? prior.get("REQUESTED_AMOUNT")==null :
                            prior.get("REQUESTED_AMOUNT") instanceof BigDecimal stored && amount.compareTo(stored)==0) ||
                    !details.equals(prior.get("DETAILS"))) throw conflict("Request ID belongs to another request");
            return prior;
        }
        return tx.execute(status -> {
            requireRead(accounts.account(id,true));
            customerRequests.insert(id,requestId,type,operation,period,amount,details,actor.id());
            events.audit(id,"CUSTOMER_"+type+"_REQUESTED",actor.id(),"SUCCESS",requestId,
                    Map.of("requestType",type));
            return customerRequests.byRequest(requestId).orElseThrow();
        });
    }
    public List<Map<String,Object>> myCustomerRequests(long id) {
        requireCustomerAccount(accounts.account(id,false));
        return customerRequests.mine(id,actor.id());
    }
    public List<Map<String,Object>> pendingCustomerRequests() {
        actor.require("ACCOUNT_OFFICER");
        return customerRequests.pending().stream().filter(row -> {
            try { requireRead(accounts.account(((Number)row.get("ACCOUNT_ID")).longValue(),false)); return true; }
            catch (ApiException | com.moneybags.common.api.BusinessException denied) { return false; }
        }).toList();
    }
    /** An officer applies an approved limit through the existing product-policy path. */
    public void decideCustomerRequest(long id,String requestId,LimitRequestDecision decision) {
        actor.require("ACCOUNT_OFFICER");
        accounts.account(id,false);
        String choice=decision.decision().toUpperCase(Locale.ROOT);
        if(!Set.of("APPROVED","REJECTED","RESOLVED").contains(choice)) throw bad("Invalid decision");
        tx.executeWithoutResult(status -> {
            accounts.account(id,true);
            var row=customerRequests.lock(requestId);
            if(((Number)row.get("ACCOUNT_ID")).longValue()!=id) throw forbidden();
            if(!"PENDING".equals(row.get("REQUEST_STATUS"))) throw conflict("Request already decided");
            if(actor.id().equals(row.get("REQUESTED_BY_USER_ID"))) throw forbidden();
            boolean limit="LIMIT_CHANGE".equals(row.get("REQUEST_TYPE"));
            authorize(limit?"ACCOUNT_LIMIT_CHANGE":"ACCOUNT_RESTRICT",id);
            if(limit && "RESOLVED".equals(choice) || !limit && "APPROVED".equals(choice))
                throw bad("Decision does not match request type");
            if("REJECTED".equals(choice) && (decision.reason()==null || decision.reason().isBlank()))
                throw bad("Explain the rejection");
            Long applied=null;
            if("APPROVED".equals(choice)) {
                if(decision.productLimitRuleId()==null && decision.overridePolicyId()==null)
                    throw bad("Choose an approved product limit rule or override policy");
                LimitCommand command=new LimitCommand(requestId,"CUSTOMER_REQUEST",
                        String.valueOf(row.get("OPERATION_CODE")),null,String.valueOf(row.get("PERIOD_CODE")),
                        "CALENDAR","Asia/Kolkata",(BigDecimal)row.get("REQUESTED_AMOUNT"),
                        decision.productLimitRuleId(),decision.overridePolicyId(),
                        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString(),null,
                        decision.approvedByUserId());
                addLimit(id,command);
                applied=customerRequests.appliedLimitId(requestId);
                if(applied==null)throw conflict("Approved limit was not recorded");
            }
            customerRequests.decide(requestId,choice,decision.reason(),applied,actor.id());
            events.audit(id,"CUSTOMER_REQUEST_DECIDED",actor.id(),"SUCCESS",requestId,
                    Map.of("decision",choice,"requestType",String.valueOf(row.get("REQUEST_TYPE"))));
        });
    }
    /** Saves an approved rate exception bound to the account's current version. */
    public void addInterestOverride(long id, InterestCommand command) {
        actor.require("ACCOUNT_OFFICER");
        AccountView a = accounts.account(id, false); authorize("ACCOUNT_INTEREST_OVERRIDE", id);
        requireMutable(a);
        if (actor.id().equals(command.approvedByUserId())) throw bad("Approver must differ from creator");
        if (command.effectiveTo() != null && !command.effectiveTo().isAfter(command.effectiveFrom()))
            throw bad("Interest override end must follow start");
        if (!peers.policy(a.productVersionId(), "INTEREST", command.overridePolicyId(), command.ratePct()).allowed())
            throw conflict("Product policy denies this rate");
        tx.executeWithoutResult(status -> {
            AccountView locked = accounts.account(id, true);
            requireMutable(locked);
            if (locked.productVersionId() != a.productVersionId()) throw conflict("Product version changed");
            approvals.consume(id,"INTEREST",command.requestId(),command);
            if (workflows.interestOverlap(id, command)) throw conflict("Interest override windows overlap");
            workflows.insertInterest(id, a.productVersionId(), command, actor.id());
            events.outbox(id, "ACCOUNT_INTEREST_OVERRIDE_CHANGED", command.requestId(),
                    Map.of("accountId", id, "productVersionId", a.productVersionId(),
                            "override", command));
        });
    }
    /** Adopts an approved product version; existing overrides must first be resolved. */
    public void adoptVersion(long id, VersionCommand command) {
        actor.require("ACCOUNT_OFFICER");
        AccountView a = accounts.account(id, false); authorize("ACCOUNT_PRODUCT_VERSION_ADOPT", id);
        requireMutable(a);
        if (a.productVersionId() == command.targetVersionId()) throw conflict("Version already adopted");
        ModuleClients.Treatment treatment = peers.treatment(a.productVersionId(), command.targetVersionId(), command.treatmentId());
        if (!treatment.allowed() || treatment.consentRequired() == null || (Boolean.TRUE.equals(treatment.consentRequired()) &&
                (command.consentReference() == null || command.consentReference().isBlank()))) throw conflict("Product treatment or consent is missing");
        ModuleClients.ProductDecision target = peers.product(a.productId(), command.targetVersionId(), a.branchCode(), a.primaryCifId());
        if (!"ACTIVE".equals(target.versionState()) || !"INR".equals(target.currency()))
            throw conflict("Target product version is not currently active");
        tx.executeWithoutResult(status -> {
            AccountView locked = accounts.account(id, true);
            requireMutable(locked);
            if (locked.productVersionId() != a.productVersionId()) throw conflict("Product version changed");
            if (workflows.activeOverrides(id)) throw conflict("Resolve active limits and rate overrides before adoption");
            workflows.adopt(id, a.productVersionId(), command, actor.id(), command.requestId());
            events.outbox(id, "ACCOUNT_PRODUCT_VERSION_ADOPTED", command.requestId(),
                    Map.of("accountId", id, "fromVersion", a.productVersionId(), "toVersion", command.targetVersionId()));
        });
    }
    /** Begins closure and requests a closed Module 5 posting fence. */
    public CommandResult requestClosure(long id, ReasonCommand command) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_CLOSE_REQUEST", id);
        return requestClosureChecked(id, command);
    }
    /** Customer may request review only for their own sole-operated, fully settled account. */
    public CommandResult requestClosureSelf(long id, ReasonCommand command) {
        AccountView a = accounts.account(id, false);
        requireDemoCustomer(a.primaryCifId());
        if (!"SELF_OPERATED".equals(a.operationMode()) || !accounts.activePrimary(id, a.primaryCifId()) ||
                accounts.activeParties(id).stream().anyMatch(p -> !"PRIMARY_HOLDER".equals(p.get("PARTY_ROLE"))))
            throw forbidden();
        if (!peers.closureFundsSettled(id).cleared()) throw conflict("Transfer or withdraw the balance and clear holds before requesting closure");
        if (!peers.paymentClearance(id).cleared() || !peers.loanClearance(id).cleared() ||
                !peers.termDepositClearance(id).cleared())
            throw conflict("Settle payments, loans and fixed deposits before requesting closure");
        return requestClosureChecked(id, command);
    }
    private CommandResult requestClosureChecked(long id, ReasonCommand command) {
        if (workflows.closureByRequest(command.requestId()).isPresent()) {
            Map<String, Object> prior = workflows.closureByRequest(command.requestId()).orElseThrow();
            if (((Number) prior.get("ACCOUNT_ID")).longValue() != id) throw conflict("Request ID belongs to another account");
            return new CommandResult(id, String.valueOf(prior.get("REQUEST_STATUS")), command.requestId(), null);
        }
        String eventId = UUID.randomUUID().toString();
        return tx.execute(status -> {
            AccountView a = accounts.account(id, true);
            if (!Set.of("ACTIVE", "DORMANT").contains(a.lifecycleStatus())) throw conflict("Account cannot enter closure");
            if (!peers.termDepositClearance(id).cleared()) throw conflict("An active fixed deposit must mature before closing its funding account");
            if (workflows.liveRestriction(id)) throw conflict("Resolve live restrictions before closure");
            workflows.requestClosure(id, command, a.lifecycleStatus(), actor.id(), command.requestId());
            accounts.lifecycle(id, "CLOSING", "CLOSING", actor.id());
            accounts.statusHistory(id, "LIFECYCLE", a.accountStatus(), "CLOSING", command.reasonCode(),
                    command.remarks(), actor.id(), command.requestId());
            long version = workflows.insertControl(id, null, command.requestId(), "APPLY", "LIFECYCLE", null, eventId);
            events.controlOutbox(eventId, id, command.requestId(), Map.of("accountId", id,
                    "controlVersion", version, "targetLifecycle", "CLOSING", "action", "APPLY",
                    "controlType", "LIFECYCLE"));
            events.outbox(id, "ACCOUNT_CLOSURE_REQUESTED", command.requestId(),
                    Map.of("accountId", id, "requestId", command.requestId()));
            return new CommandResult(id, "PENDING_MODULE5_ACK", command.requestId(), version);
        });
    }
    /** Approves closure only after a closed fence and fresh Txn/Payment/Loan clearances. */
    public void approveClosure(long id, String requestId) {
        actor.require("ACCOUNT_APPROVER"); authorize("ACCOUNT_CLOSE_APPROVE", id);
        AccountView before = accounts.account(id, false);
        Map<String, Object> control = workflows.latestLifecycleControl(id).orElseThrow(() -> conflict("Missing lifecycle control"));
        if (!"ACKNOWLEDGED".equals(control.get("SYNC_STATUS")) ||
                !requestId.equals(control.get("REQUEST_ID")) ||
                !"APPLY".equals(control.get("TARGET_ACTION")))
            throw conflict("Module 5 fence has not acknowledged this closure");
        long controlVersion = ((Number) control.get("CONTROL_VERSION")).longValue();
        ModuleClients.Clearance txn = peers.transactionClearance(id, controlVersion);
        ModuleClients.Clearance pay = peers.paymentClearance(id);
        ModuleClients.Clearance loan = peers.loanClearance(id);
        ModuleClients.Clearance termDeposit = peers.termDepositClearance(id);
        if (!txn.cleared() || !pay.cleared() || !loan.cleared() || !termDeposit.cleared() ||
                txn.reference() == null || txn.reference().isBlank() ||
                pay.reference() == null || pay.reference().isBlank() ||
                loan.reference() == null || loan.reference().isBlank() ||
                termDeposit.reference() == null || termDeposit.reference().isBlank())
            throw conflict("Financial or payment obligations remain");
        String clearance = clearanceDigest(txn.reference(), pay.reference(), loan.reference(), termDeposit.reference());
        tx.executeWithoutResult(status -> {
            AccountView a = accounts.account(id, true);
            if (a.rowVersion() != before.rowVersion() || !"CLOSING".equals(a.lifecycleStatus()))
                throw conflict("Account changed after clearance; retry checks");
            Map<String, Object> closure = workflows.closure(id, requestId);
            if (!"PENDING".equals(closure.get("REQUEST_STATUS"))) throw conflict("Closure request is not pending");
            if (actor.id().equals(closure.get("REQUESTED_BY_USER_ID"))) throw forbidden();
            if (workflows.liveRestriction(id)) throw conflict("Restriction remains active");
            workflows.decideClosure(id, requestId, "APPROVED", actor.id(), clearance, a.rowVersion(), null);
            accounts.lifecycle(id, "CLOSED", "CLOSED", actor.id());
            accounts.statusHistory(id, "LIFECYCLE", "CLOSING", "CLOSED", "CLOSURE_APPROVED", null,
                    actor.id(), requestId);
            events.outbox(id, "ACCOUNT_CLOSED", requestId, Map.of("accountId", id, "clearanceRef", clearance));
            events.audit(id, "ACCOUNT_CLOSE_APPROVE", actor.id(), "SUCCESS", requestId,
                    Map.of("transactionClearance", txn.reference(), "paymentClearance", pay.reference(),
                            "loanClearance", loan.reference(), "termDepositClearance", termDeposit.reference()));
        });
    }
    /** Rejects closure, restores the previous lifecycle, and requests a new posting fence. */
    public CommandResult rejectClosure(long id, String requestId, String reason) {
        actor.require("ACCOUNT_APPROVER"); authorize("ACCOUNT_CLOSE_REJECT", id);
        if (reason == null || reason.isBlank()) throw bad("Rejection reason required");
        String eventId = UUID.randomUUID().toString();
        return tx.execute(status -> {
            AccountView a = accounts.account(id, true);
            if (!"CLOSING".equals(a.lifecycleStatus())) throw conflict("Account is not closing");
            Map<String, Object> closure = workflows.closure(id, requestId);
            if (!"PENDING".equals(closure.get("REQUEST_STATUS"))) throw conflict("Closure request is not pending");
            if (actor.id().equals(closure.get("REQUESTED_BY_USER_ID"))) throw forbidden();
            String previous = (String) closure.get("PRE_CLOSURE_LIFECYCLE_STATUS");
            workflows.decideClosure(id, requestId, "REJECTED", actor.id(), null, a.rowVersion(), reason);
            accounts.lifecycle(id, previous, previous, actor.id());
            accounts.statusHistory(id, "LIFECYCLE", "CLOSING", previous, "CLOSURE_REJECTED", reason,
                    actor.id(), requestId);
            long version = workflows.insertControl(id, null, requestId + ":restore", "APPLY", "LIFECYCLE", null, eventId);
            events.controlOutbox(eventId, id, requestId, Map.of("accountId", id,
                    "controlVersion", version, "targetLifecycle", previous, "action", "APPLY", "controlType", "LIFECYCLE"));
            events.outbox(id, "ACCOUNT_CLOSURE_REJECTED", requestId,
                    Map.of("accountId", id, "restoredLifecycle", previous));
            return new CommandResult(id, "PENDING_MODULE5_ACK", requestId, version);
        });
    }
    /** Creates a majority review after CIF confirms the primary holder is now adult. */
    public void startMajority(long id, String requestId, OffsetDateTime dueAt) {
        actor.require("ACCOUNT_OFFICER"); authorize("ACCOUNT_MAJORITY_REVIEW", id);
        if (dueAt == null || !dueAt.isAfter(OffsetDateTime.now())) throw bad("Review dueAt must be in the future");
        AccountView a = accounts.account(id, false);
        requireMutable(a);
        if (!"GUARDIAN_OPERATED".equals(a.operationMode())) throw conflict("Account is not guardian operated");
        if (!Boolean.FALSE.equals(peers.customer(a.primaryCifId(), a.branchCode()).minor()))
            throw conflict("CIF still identifies a minor");
        tx.executeWithoutResult(status -> {
            requireMutable(accounts.account(id, true));
            workflows.startMajorityReview(id, requestId, dueAt);
            events.outbox(id, "ACCOUNT_MAJORITY_REVIEW_STARTED", requestId, Map.of("accountId", id));
        });
    }
    /** Completes or escalates majority review without silently changing guardian rights. */
    public void decideMajority(long id, MajorityDecision command) {
        actor.require("ACCOUNT_APPROVER"); authorize("ACCOUNT_MAJORITY_DECIDE", id);
        if (!Set.of("COMPLETED", "ESCALATED").contains(command.decision())) throw bad("Invalid majority decision");
        if ("COMPLETED".equals(command.decision()) &&
                (command.consentReference() == null || command.consentReference().isBlank())) throw bad("Consent reference required");
        AccountView before = accounts.account(id, false);
        requireMutable(before);
        if ("COMPLETED".equals(command.decision()) && !"GUARDIAN_OPERATED".equals(before.operationMode()))
            throw conflict("Account is not guardian operated");
        if (!Boolean.FALSE.equals(peers.customer(before.primaryCifId(), before.branchCode()).minor()))
            throw conflict("CIF still identifies a minor");
        tx.executeWithoutResult(status -> {
            requireMutable(accounts.account(id, true));
            if (workflows.decideMajority(id, command.requestId(), command.decision(), actor.id(), command.reason()) == 0)
                throw conflict("Pending majority review not found");
            if ("COMPLETED".equals(command.decision())) {
                if (!Set.of("SELF_OPERATED", "ANYONE", "JOINTLY").contains(command.newOperationMode())) throw bad("Invalid adult operation mode");
                if ("JOINTLY".equals(command.newOperationMode()) && !accounts.hasActiveRole(id, "JOINT_HOLDER"))
                    throw conflict("Joint operation requires a joint holder");
                accounts.endGuardians(id);
                accounts.operationMode(id, command.newOperationMode(), actor.id());
            } else accounts.majorityStatus(id, "ESCALATED", actor.id());
            events.outbox(id, "ACCOUNT_MAJORITY_REVIEW_DECIDED", command.requestId(),
                    Map.of("accountId", id, "decision", command.decision()));
        });
    }

    /** Requires both an officer role and a current IAM decision for a sensitive action. */
    private ModuleClients.IamDecision authorize(String action, Long id) {
        ModuleClients.IamDecision result = peers.authorize(actor.id(), action, id);
        if (!result.allowed() || result.decisionRef() == null) throw forbidden();
        return result;
    }
    /** Enforces role-plus-party access for account details. */
    private void requireRead(AccountView account) {
        peers.read(account.id());
    }
    private void requireCustomerAccount(AccountView account) {
        if(!"CUSTOMER".equals(CurrentActor.get().userType())) throw forbidden();
        requireRead(account);
    }
    private void requireSolePrimaryCustomer(AccountView account) {
        requireCustomerAccount(account);
        if(!CurrentActor.get().cifIds().contains(account.primaryCifId()) ||
                !accounts.activePrimary(account.id(),account.primaryCifId()) ||
                !"SELF_OPERATED".equals(account.operationMode()) ||
                accounts.activeParties(account.id()).stream().anyMatch(p -> !"PRIMARY_HOLDER".equals(p.get("PARTY_ROLE"))))
            throw forbidden();
    }
    /** Blocks administrative changes after an account enters closing or a terminal state. */
    private void requireMutable(AccountView account) {
        if (!Set.of("PENDING_OPEN", "ACTIVE", "DORMANT").contains(account.lifecycleStatus()))
            throw conflict("Account lifecycle does not permit this change");
    }
    /** Checks the initial supported product, KYC, and minor-operation rules. */
    private void validateOpening(OpenAccount c, ModuleClients.CustomerDecision customer,
                                 ModuleClients.ProductDecision product) {
        if (!"ACTIVE".equals(customer.status()) || !"VERIFIED".equals(customer.kycStatus()))
            throw conflict("Customer or KYC is not eligible");
        if (customer.minor() == null || !Set.of("SAVINGS", "CURRENT").contains(Objects.toString(product.productType(), "")) ||
                !"INR".equals(product.currency()) || !"ACTIVE".equals(product.versionState()) ||
                product.ruleSetHash() == null || !product.ruleSetHash().matches("[0-9a-fA-F]{64}"))
            throw conflict("Product version is not openable");
        if (Boolean.TRUE.equals(customer.minor()) != "GUARDIAN_OPERATED".equals(c.operationMode()))
            throw conflict("Operation mode does not match minor status");
        if (!Set.of("SELF_OPERATED", "ANYONE", "JOINTLY", "GUARDIAN_OPERATED").contains(c.operationMode()))
            throw bad("Invalid operation mode");
    }
    /** Checks type-specific amounts and source-reference pair rules before insertion. */
    private void validateRestriction(RestrictionCommand c) {
        if (!Set.of("FREEZE", "DEBIT_BLOCK", "CREDIT_BLOCK", "LIEN", "PARTIAL_BLOCK").contains(c.type())) throw bad("Invalid restriction type");
        boolean amountRequired = Set.of("LIEN", "PARTIAL_BLOCK").contains(c.type());
        if (amountRequired != (c.amount() != null) || (amountRequired && c.amount().signum() <= 0)) throw bad("Invalid restriction amount");
        if ((c.sourceSystem() == null) != (c.sourceReference() == null)) throw bad("Source system and reference must be paired");
    }
    /** Builds a control payload without placing nullable values in Map.of. */
    private Map<String, Object> controlPayload(long id, long version, long restrictionId,
                                               String action, String type, BigDecimal amount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", id); payload.put("controlVersion", version);
        payload.put("restrictionId", restrictionId); payload.put("action", action);
        payload.put("controlType", type); payload.put("amount", amount);
        return payload;
    }
    /** Treats absent additional parties as an empty list. */
    private List<PartyInput> nullSafe(List<PartyInput> parties) { return parties == null ? List.of() : parties; }
    /** Fits durable clearance references into the schema's 80-character field. */
    private String clearanceDigest(String... references) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", references).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
    /** Returns a consistent bad-request error. */
    private ApiException bad(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    /** Returns a consistent conflict error. */
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message); }
    /** Returns a consistent permission error. */
    private ApiException forbidden() { return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not authorized for this account action"); }
}
