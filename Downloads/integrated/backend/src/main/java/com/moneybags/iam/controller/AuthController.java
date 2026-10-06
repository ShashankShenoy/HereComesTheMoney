package com.moneybags.iam.controller;
import com.moneybags.common.api.*;
import com.moneybags.iam.dto.*;
import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.iam.service.LoginService;
import com.moneybags.iam.service.CustomerSignupService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import java.util.*;
@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final LoginService login;
    private final CustomerSignupService signup;
    public AuthController(LoginService login,CustomerSignupService signup) { this.login=login;this.signup=signup; }
    @GetMapping("/signup-availability") public ApiResponse<Map<String,Boolean>> signupAvailability() { return ApiResponse.of(Map.of("enabled",signup.enabled())); }
    @PostMapping("/signup") public ApiResponse<Map<String,String>> signup(@Valid @RequestBody CustomerSignupRequest request) { return ApiResponse.of(signup.register(request)); }
    @PostMapping("/login") public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) { return ApiResponse.of(login.login(request)); }
    @PostMapping("/refresh") public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) { return ApiResponse.of(login.refresh(request)); }
    @GetMapping("/me") public ApiResponse<UserPrincipal> me(@AuthenticationPrincipal UserPrincipal user) { return ApiResponse.of(user); }
    @GetMapping("/sessions") public ApiResponse<List<SessionResponse>> sessions(@AuthenticationPrincipal UserPrincipal user) { return ApiResponse.of(login.sessions(user)); }
    @DeleteMapping("/sessions/{sessionId}") public ApiResponse<Map<String,String>> revoke(@AuthenticationPrincipal UserPrincipal user,@PathVariable String sessionId) { login.revoke(user,sessionId);return ApiResponse.of(Map.of("status","REVOKED")); }
    @PostMapping("/logout") public ApiResponse<Map<String,String>> logout(@AuthenticationPrincipal UserPrincipal user) { login.revoke(user,user.sessionId());return ApiResponse.of(Map.of("status","REVOKED")); }
}
