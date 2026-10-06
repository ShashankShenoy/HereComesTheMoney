package com.moneybags.iam.service;

import com.moneybags.common.api.*;
import com.moneybags.iam.repository.IamRepository;
import com.moneybags.iam.dto.*;
import com.moneybags.iam.model.*;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

@Service
public class LoginService {
    private final IamRepository repository;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final int idleMinutes,absoluteHours,maxAttempts,lockMinutes;
    private final String dummyHash;
    private final SecureRandom random=new SecureRandom();
    @org.springframework.beans.factory.annotation.Autowired private MfaService mfa;
    public LoginService(IamRepository repository,PasswordEncoder passwords,Clock clock,
            @Value("${moneybags.auth.idle-minutes:30}") int idleMinutes,
            @Value("${moneybags.auth.absolute-hours:8}") int absoluteHours,
            @Value("${moneybags.auth.max-failed-attempts:5}") int maxAttempts,
            @Value("${moneybags.auth.lock-minutes:15}") int lockMinutes) {
        this.repository=repository;this.passwords=passwords;this.clock=clock;
        this.idleMinutes=idleMinutes;this.absoluteHours=absoluteHours;this.maxAttempts=maxAttempts;this.lockMinutes=lockMinutes;
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    private OffsetDateTime now() { return OffsetDateTime.now(clock); }
    private static BusinessException invalidLogin() { return new BusinessException(HttpStatus.UNAUTHORIZED,"INVALID_LOGIN","Username or password is incorrect, or login is unavailable"); }
    private static BusinessException invalidSession() { return new BusinessException(HttpStatus.UNAUTHORIZED,"SESSION_INVALID","Session is expired or revoked; sign in again"); }

    // Failed attempt counts and audit records must commit when authentication is rejected.
    @Transactional(noRollbackFor=BusinessException.class)
    public LoginResponse login(LoginRequest request) {
        String username=request.username().trim();OffsetDateTime now=now();
        var user=repository.userByName(username).orElse(null);
        if(user==null) { safeMatches(request.password(),dummyHash);repository.audit("LOGIN_FAILED",null,null,null,"DENIED",Correlation.current(),now);throw invalidLogin(); }
        var credential=repository.credential(user.userId(),true).orElse(null);
        if(credential==null||!"ACTIVE".equals(user.status())||!"ACTIVE".equals(credential.status())||
                (credential.lockedUntil()!=null&&credential.lockedUntil().isAfter(now))) {
            safeMatches(request.password(),dummyHash);repository.audit("LOGIN_FAILED",user.userId(),null,user.userId(),"DENIED",Correlation.current(),now);throw invalidLogin();
        }
        // Existing hashes must explicitly identify their algorithm. Never treat plaintext as a hash.
        if(!"BCRYPT".equalsIgnoreCase(credential.hashScheme())) throw new BusinessException(HttpStatus.UNAUTHORIZED,"CREDENTIAL_RESET_REQUIRED","Credential algorithm is unsupported; ask an administrator to reset it");
        if(!safeMatches(request.password(),credential.passwordHash())) {
            int previous=credential.lockedUntil()!=null&&!credential.lockedUntil().isAfter(now)?0:credential.failedAttempts().intValue();
            int failed=previous+1;repository.failedAttempt(user.userId(),failed,failed>=maxAttempts?now.plusMinutes(lockMinutes):null);
            repository.audit("LOGIN_FAILED",user.userId(),null,user.userId(),"DENIED",Correlation.current(),now);throw invalidLogin();
        }
        if(mfa!=null) {
            try {mfa.verifyLogin(user.userId(),request.otp());}
            catch(BusinessException error) {
                if(!"MFA_REQUIRED".equals(error.code())) {
                    int previous=credential.lockedUntil()!=null&&!credential.lockedUntil().isAfter(now)?0:credential.failedAttempts().intValue();
                    int failed=previous+1;
                    repository.failedAttempt(user.userId(),failed,failed>=maxAttempts?now.plusMinutes(lockMinutes):null);
                }
                repository.audit("MFA_LOGIN_DENIED",user.userId(),null,user.userId(),"DENIED",Correlation.current(),now);
                throw error;
            }
        }
        repository.failedAttempt(user.userId(),0,null);
        String session=UUID.randomUUID().toString();OffsetDateTime idle=now.plusMinutes(idleMinutes),absolute=now.plusHours(absoluteHours);
        repository.createSession(session,user.userId(),blankDefault(request.clientId(),"MONEYBAGS_WEB"),blankDefault(request.deviceRef(),"Browser"),now,idle,absolute);
        if(mfa!=null&&mfa.active(user.userId()))repository.setAuthLevel(session,mfa.authLevel());
        String refresh=randomToken();repository.createRefresh(session,UUID.randomUUID().toString(),hash(refresh),now,absolute);
        repository.audit("LOGIN_SUCCESS",user.userId(),session,user.userId(),"SUCCESS",Correlation.current(),now);
        return new LoginResponse(session,"Bearer",refresh,idle,absolute,principal(user,session,now));
    }
    @Transactional(noRollbackFor=BusinessException.class)
    public UserPrincipal authenticate(String token) {
        try { UUID.fromString(token); } catch(IllegalArgumentException e) { throw invalidSession(); }
        var session=repository.session(token,true).orElseThrow(LoginService::invalidSession);
        OffsetDateTime now=now();checkSession(session,now);
        var user=repository.user(session.userId()).orElseThrow(LoginService::invalidSession);
        if(!"ACTIVE".equals(user.status())) throw invalidSession();
        OffsetDateTime idle=now.plusMinutes(idleMinutes);if(idle.isAfter(session.absoluteExpiresAt()))idle=session.absoluteExpiresAt();
        repository.touch(session.sessionId(),now,idle);
        return principal(user,session.sessionId(),now);
    }
    @Transactional(noRollbackFor=BusinessException.class)
    public LoginResponse refresh(RefreshRequest request) {
        var candidate=repository.refresh(hash(request.refreshToken()),false).orElseThrow(LoginService::invalidSession);
        // Lock session before token everywhere to prevent lock-order deadlocks.
        var session=repository.session(candidate.sessionId(),true).orElseThrow(LoginService::invalidSession);
        var token=repository.refresh(hash(request.refreshToken()),true).orElseThrow(LoginService::invalidSession);
        OffsetDateTime now=now();
        if(!"ACTIVE".equals(token.status())) {
            repository.endSession(session.sessionId(),"COMPROMISED",now);repository.revokeRefresh(session.sessionId());repository.markRefreshReused(token.tokenId(),now);
            repository.audit("REFRESH_REUSE",session.userId(),session.sessionId(),session.userId(),"DENIED",Correlation.current(),now);
            throw new BusinessException(HttpStatus.UNAUTHORIZED,"REFRESH_REUSE","Refresh token reuse detected; session revoked");
        }
        checkSession(session,now);if(!token.expiresAt().isAfter(now))throw invalidSession();
        var user=repository.user(session.userId()).orElseThrow(LoginService::invalidSession);if(!"ACTIVE".equals(user.status()))throw invalidSession();
        String newToken=randomToken();String newId=repository.createRefresh(session.sessionId(),token.familyId(),hash(newToken),now,session.absoluteExpiresAt());repository.replaceRefresh(token.tokenId(),newId,now);
        OffsetDateTime idle=now.plusMinutes(idleMinutes);if(idle.isAfter(session.absoluteExpiresAt()))idle=session.absoluteExpiresAt();repository.touch(session.sessionId(),now,idle);
        return new LoginResponse(session.sessionId(),"Bearer",newToken,idle,session.absoluteExpiresAt(),principal(user,session.sessionId(),now));
    }
    public List<SessionResponse> sessions(UserPrincipal user) {
        return repository.sessions(user.userId()).stream().map(s->new SessionResponse(s.sessionId(),s.clientId(),s.deviceRef(),s.status(),s.createdAt(),s.lastActivityAt(),s.idleExpiresAt(),s.absoluteExpiresAt(),s.sessionId().equals(user.sessionId()))).toList();
    }
    @Transactional public void revoke(UserPrincipal user,String sessionId) {
        var session=repository.session(sessionId,true).orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"SESSION_NOT_FOUND","Session not found"));
        if(!session.userId().equals(user.userId())) throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","You can revoke only your own sessions");
        repository.endSession(sessionId,"REVOKED",now());repository.revokeRefresh(sessionId);repository.audit("SESSION_REVOKED",user.userId(),user.sessionId(),sessionId,"SUCCESS",Correlation.current(),now());
    }
    private void checkSession(M01IamSessionRow session,OffsetDateTime now) {
        if(!"ACTIVE".equals(session.status()))throw invalidSession();
        if(!session.idleExpiresAt().isAfter(now)||!session.absoluteExpiresAt().isAfter(now)) { repository.endSession(session.sessionId(),"EXPIRED",now);repository.revokeRefresh(session.sessionId());throw invalidSession(); }
    }
    private UserPrincipal principal(M01IamUserRow u,String session,OffsetDateTime now) { return new UserPrincipal(u.userId(),u.username(),u.userType(),session,u.entitlementVersion(),repository.roles(u.userId(),now),repository.permissions(u.userId(),now),repository.cifIds(u.userId(),now)); }
    private String randomToken() { byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private boolean safeMatches(String password,String encoded) { return password.getBytes(StandardCharsets.UTF_8).length<=72&&passwords.matches(password,encoded); }
    private static String blankDefault(String value,String fallback) { return value==null||value.isBlank()?fallback:value; }
    public static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
}
