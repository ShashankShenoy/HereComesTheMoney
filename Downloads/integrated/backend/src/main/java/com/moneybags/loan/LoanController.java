package com.moneybags.loan;

import com.moneybags.common.api.ApiResponse;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.loan.LoanDtos.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Versioned Module 8 endpoints. All write decisions are enforced again inside LoanService. */
@RestController
@RequestMapping("/api/v1/loans")
@SecurityRequirement(name="iamBearer")
public class LoanController {
    private final LoanService loans;
    public LoanController(LoanService loans){this.loans=loans;}

    /** Lists applications for one authorized branch. */
    @GetMapping("/applications")
    public ApiResponse<List<Application>> applications(@AuthenticationPrincipal UserPrincipal actor,
        @RequestParam String branch,@RequestParam(required=false) String status,@RequestParam(defaultValue="25") int limit){
        return ApiResponse.of(loans.applications(actor,branch,status,limit));
    }

    /** Starts a draft with an idempotency key. */
    @PostMapping("/applications")
    public ApiResponse<Application> create(@AuthenticationPrincipal UserPrincipal actor,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody CreateApplication body){
        return ApiResponse.of(loans.create(actor,body,key));
    }

    /** Reads one application according to its actual owner and branch. */
    @GetMapping("/applications/{id}")
    public ApiResponse<Application> application(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.application(actor,id));
    }

    /** Reads the bounded assessment history for a loan officer. */
    @GetMapping("/applications/{id}/assessments")
    public ApiResponse<List<Map<String,Object>>> assessments(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.assessments(actor,id));
    }

    /** Reads completed checker decisions and sanction terms. */
    @GetMapping("/applications/{id}/decisions")
    public ApiResponse<List<Map<String,Object>>> decisions(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.decisions(actor,id));
    }

    /** Reads issued or accepted offer summaries. */
    @GetMapping("/applications/{id}/offers")
    public ApiResponse<List<Map<String,Object>>> offers(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.offers(actor,id));
    }

    /** Reads document verification metadata without exposing storage locations. */
    @GetMapping("/applications/{id}/documents")
    public ApiResponse<List<Map<String,Object>>> documents(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.documents(actor,id));
    }

    /** Creates the immutable revision submitted to underwriting. */
    @PostMapping("/applications/{id}/submit")
    public ApiResponse<Application> submit(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.submit(actor,id));
    }

    /** Captures a human assessment and optional externally supplied score evidence. */
    @PostMapping("/applications/{id}/assessments")
    public ApiResponse<Map<String,Object>> assess(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
        @Valid @RequestBody Assessment body){return ApiResponse.of(loans.assess(actor,id,body));}

    /** Records the checker decision and authority evidence. */
    @PostMapping("/applications/{id}/decisions")
    public ApiResponse<Map<String,Object>> decide(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
        @Valid @RequestBody Decision body){return ApiResponse.of(loans.decide(actor,id,body));}

    /** Issues an approved offer with an expiry and document reference. */
    @PostMapping("/applications/{id}/offers")
    public ApiResponse<Map<String,Object>> offer(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
        @Valid @RequestBody Offer body){return ApiResponse.of(loans.offer(actor,id,body));}

    /** Accepts an offer against borrower-owned accounts. */
    @PostMapping("/offers/{id}/accept")
    public ApiResponse<Map<String,Object>> accept(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
        @Valid @RequestBody AcceptOffer body){return ApiResponse.of(loans.accept(actor,id,body));}

    /** Adds immutable document metadata to the application. */
    @PostMapping("/applications/{id}/documents")
    public ApiResponse<Map<String,Object>> addDocument(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
        @Valid @RequestBody Document body){return ApiResponse.of(loans.addDocument(actor,id,body));}

    /** Records independent human verification of received document metadata. */
    @PostMapping("/applications/{id}/documents/{documentId}/verify")
    public ApiResponse<Map<String,Object>> verifyDocument(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id,
        @PathVariable long documentId){return ApiResponse.of(loans.verifyDocument(actor,id,documentId));}

    /** Converts accepted terms into a pending facility without moving funds. */
    @PostMapping("/applications/{id}/convert")
    public ApiResponse<Map<String,Object>> convert(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.convert(actor,id));
    }

    /** Reads servicing balances as a projection with an as-of timestamp. */
    @GetMapping("/facilities/{id}")
    public ApiResponse<Map<String,Object>> facility(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.facility(actor,id));
    }

    /** Reads the current repayment schedule. */
    @GetMapping("/facilities/{id}/schedule")
    public ApiResponse<List<Map<String,Object>>> schedule(@AuthenticationPrincipal UserPrincipal actor,@PathVariable long id){
        return ApiResponse.of(loans.schedule(actor,id));
    }
}
