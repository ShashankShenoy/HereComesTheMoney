package com.moneybags.iam.controller;

import com.moneybags.common.api.*;
import com.moneybags.iam.dto.IamDtos.*;
import com.moneybags.iam.dto.CustomerSignupRequest;
import com.moneybags.iam.model.*;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.iam.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import java.util.*;

@RestController @RequestMapping("/api/v1/iam")
public class IamController {
    private final com.moneybags.integration.CustomerHashController customerAccess;private final IamAdminService service;private final MfaService mfa;private final AccessDecisionService access;private final CustomerSignupService signup;
    public IamController(IamAdminService service,MfaService mfa,AccessDecisionService access,com.moneybags.integration.CustomerHashController customerAccess,CustomerSignupService signup){this.customerAccess=customerAccess;this.service=service;this.mfa=mfa;this.access=access;this.signup=signup;}
    @GetMapping("/dashboard") public ApiResponse<Map<String,Object>> dashboard(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(service.dashboard(p));}
    @GetMapping("/users") public ApiResponse<Map<String,Object>> users(@AuthenticationPrincipal UserPrincipal p,@RequestParam(defaultValue="")String search,@RequestParam(defaultValue="")String status,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return ApiResponse.of(service.users(p,search,status,page,size));}
    @PostMapping("/users") public ApiResponse<Map<String,Object>> create(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody CreateUser r){return ApiResponse.of(customerAccess.create(r));}
    @PostMapping("/users/demo-customer") public ApiResponse<Map<String,String>> demoCustomer(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody CustomerSignupRequest r){return ApiResponse.of(signup.registerAsAdmin(p,r));}
    @PostMapping("/setup/checker") public ApiResponse<M01IamUserRow> checker(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody CreateUser r){return ApiResponse.of(service.setupChecker(p,r));}
    @GetMapping("/users/{id}") public ApiResponse<Map<String,Object>> user(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id){return ApiResponse.of(service.userDetail(p,id));}
    @PatchMapping("/users/{id}/status") public ApiResponse<M01IamUserRow> status(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody StatusChange r){return ApiResponse.of(service.changeStatus(p,id,r));}
    @PostMapping("/users/{id}/password-reset") public ApiResponse<Map<String,String>> reset(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody PasswordReset r){service.resetPassword(p,id,r);return ok("Password reset; existing sessions ended");}
    @PostMapping("/users/{id}/factor-reset") public ApiResponse<Map<String,String>> resetFactors(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody ReasonInput r){service.resetFactors(p,id,r);return ok("Authenticator factors reset; existing sessions ended");}
    @DeleteMapping("/users/{id}/sessions/{session}") public ApiResponse<Map<String,String>> session(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@PathVariable String session){service.revokeSession(p,id,session);return ok("Session revoked");}
    @PostMapping("/users/{id}/customer-links") public ApiResponse<M01IamCustomerLinkRow> link(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody LinkInput r){return ApiResponse.of(service.link(p,id,r));}
    @DeleteMapping("/users/{id}/customer-links/{link}") public ApiResponse<Map<String,String>> unlink(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@PathVariable String link){service.revokeLink(p,id,link);return ok("Link revoked");}
    @GetMapping("/roles") public ApiResponse<List<M01IamRoleRow>> roles(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(service.roles(p));}
    @PostMapping("/roles") public ApiResponse<M01IamRoleRow> role(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody RoleInput r){return ApiResponse.of(service.createRole(p,r));}
    @GetMapping("/roles/{id}") public ApiResponse<Map<String,Object>> roleDetail(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id){return ApiResponse.of(service.roleDetail(p,id));}
    @PutMapping("/roles/{id}") public ApiResponse<M01IamRoleRow> updateRole(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody RoleUpdate r){return ApiResponse.of(service.updateRole(p,id,r));}
    @PostMapping("/roles/{id}/authorities") public ApiResponse<M01IamRoleAuthorityRow> authority(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody AuthorityInput r){return ApiResponse.of(service.authority(p,id,r));}
    @DeleteMapping("/roles/{id}/authorities/{authority}") public ApiResponse<Map<String,String>> expire(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@PathVariable String authority){service.expireAuthority(p,id,authority);return ok("Authority ended");}
    @GetMapping("/permissions") public ApiResponse<List<M01IamPermissionRow>> permissions(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(service.permissions(p));}
    @PostMapping("/permissions") public ApiResponse<M01IamPermissionRow> permission(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody PermissionInput r){return ApiResponse.of(service.createPermission(p,r));}
    @GetMapping("/access-requests") public ApiResponse<List<M01IamAccessRequestRow>> requests(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(service.requests(p));}
    @PostMapping("/access-requests") public ApiResponse<M01IamAccessRequestRow> request(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody AccessInput r){return ApiResponse.of(service.submitAccess(p,r));}
    @GetMapping("/access-requests/{id}") public ApiResponse<Map<String,Object>> requestDetail(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id){return ApiResponse.of(service.requestDetail(p,id));}
    @PostMapping("/access-requests/{id}/decision") public ApiResponse<M01IamAccessRequestRow> decision(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody Decision r){return ApiResponse.of(service.decide(p,id,r));}
    @GetMapping("/audit") public ApiResponse<List<M01IamAuditEventRow>> audit(@AuthenticationPrincipal UserPrincipal p,@RequestParam(defaultValue="")String search){return ApiResponse.of(service.audits(p,search));}
    @GetMapping("/outbox") public ApiResponse<List<Map<String,Object>>> outbox(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(service.outbox(p));}
    @GetMapping("/menus") public ApiResponse<List<M01IamMenuItemRow>> menus(@AuthenticationPrincipal UserPrincipal p,@RequestParam(defaultValue="false")boolean all){return ApiResponse.of(service.menus(p,all));}
    @PostMapping("/menus") public ApiResponse<M01IamMenuItemRow> menu(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody MenuInput r){return ApiResponse.of(service.menu(p,null,r));}
    @PutMapping("/menus/{id}") public ApiResponse<M01IamMenuItemRow> menuUpdate(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody MenuInput r){return ApiResponse.of(service.menu(p,id,r));}
    @PostMapping("/authorization/check") public ApiResponse<AccessDecisionService.Result> check(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody AuthorizationInput r){return ApiResponse.of(access.evaluate(p,r));}
    @PostMapping("/password") public ApiResponse<Map<String,String>> password(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody PasswordChange r){service.changePassword(p,r);return ok("Password changed; sign in again");}
    @GetMapping("/factors") public ApiResponse<List<FactorView>> factors(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(mfa.factors(p));}
    @PostMapping("/factors/totp") public ApiResponse<Enrollment> enroll(@AuthenticationPrincipal UserPrincipal p){return ApiResponse.of(mfa.enroll(p));}
    @PostMapping("/factors/{id}/confirm") public ApiResponse<Map<String,String>> confirm(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody Code r){mfa.confirm(p,id,r);return ok("Authenticator enabled");}
    @PostMapping("/factors/{id}/revoke") public ApiResponse<Map<String,String>> revokeFactor(@AuthenticationPrincipal UserPrincipal p,@PathVariable String id,@Valid @RequestBody FactorRevoke r){mfa.revoke(p,id,r);return ok("Authenticator removed");}
    @PostMapping("/step-up") public ApiResponse<Map<String,String>> stepUp(@AuthenticationPrincipal UserPrincipal p,@Valid @RequestBody Code r){mfa.stepUp(p,r);return ok("MFA verified for five minutes");}
    private static ApiResponse<Map<String,String>> ok(String message){return ApiResponse.of(Map.of("message",message));}
}
