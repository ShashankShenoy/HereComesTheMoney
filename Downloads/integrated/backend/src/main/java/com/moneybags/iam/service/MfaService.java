package com.moneybags.iam.service;

import com.moneybags.common.api.*;
import com.moneybags.common.database.SchemaTable;
import com.moneybags.iam.dto.IamDtos.*;
import com.moneybags.iam.model.*;
import com.moneybags.iam.repository.*;
import com.moneybags.iam.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.moneybags.iam.repository.IamRepository.map;

/** TOTP implementation using JDK crypto. Factor references store AES-GCM ciphertext, not the shared secret. */
@Service
public class MfaService {
    private static final String ALPHABET="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private final IamAdminRepository db;private final IamRepository auth;private final Clock clock;
    private final PasswordEncoder passwords;private final SecretKey key;private final SecureRandom random=new SecureRandom();
    public MfaService(IamAdminRepository db,IamRepository auth,Clock clock,PasswordEncoder passwords,
        @Value("${moneybags.auth.mfa-key-file:runtime/mfa.key}")String keyFile) {
        this.db=db;this.auth=auth;this.clock=clock;this.passwords=passwords;
        try {
            Path path=Path.of(keyFile).toAbsolutePath().normalize();Files.createDirectories(path.getParent());
            if(!Files.exists(path)){byte[] bytes=new byte[32];random.nextBytes(bytes);try{Files.writeString(path,Base64.getEncoder().encodeToString(bytes),StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException ignored){}}
            byte[] bytes=Base64.getDecoder().decode(Files.readString(path).trim());if(bytes.length!=32)throw new IllegalStateException("MFA key must be 32 bytes");key=new SecretKeySpec(bytes,"AES");
        }catch(Exception e){throw new IllegalStateException("Cannot load local MFA encryption key; check its file permissions",e);}
    }
    private OffsetDateTime now(){return OffsetDateTime.now(clock);}
    public List<FactorView> factors(UserPrincipal actor){return db.rows("SELECT * FROM M01_IAM_AUTH_FACTOR WHERE USER_ID=? ORDER BY ENROLLED_AT DESC",M01IamAuthFactorRow.class,actor.userId()).stream().map(f->new FactorView(f.factorId(),f.factorType(),f.status(),f.enrolledAt(),f.verifiedAt(),f.revokedAt())).toList();}
    @Transactional public Enrollment enroll(UserPrincipal actor){
        // User lock serializes enrollment, and only one active TOTP is supported by this demo.
        db.user(actor.userId(),true);
        if(db.count("SELECT COUNT(*) FROM M01_IAM_AUTH_FACTOR WHERE USER_ID=? AND FACTOR_TYPE='TOTP' AND STATUS='ACTIVE'",actor.userId())>0)
            throw new BusinessException(HttpStatus.CONFLICT,"MFA_ALREADY_ENABLED","Remove the existing authenticator before replacing it");
        db.jdbc().update("UPDATE M01_IAM_AUTH_FACTOR SET STATUS='REVOKED',REVOKED_AT=? WHERE USER_ID=? AND FACTOR_TYPE='TOTP' AND STATUS='PENDING'",now(),actor.userId());
        byte[] secretBytes=new byte[20];random.nextBytes(secretBytes);String secret=base32(secretBytes),factor=UUID.randomUUID().toString();
        db.insert(SchemaTable.M01_IAM_AUTH_FACTOR,map("FACTOR_ID",factor,"USER_ID",actor.userId(),"FACTOR_TYPE","TOTP","FACTOR_REF",encrypt(secret),"STATUS","PENDING","ENROLLED_AT",now()));
        auth.audit("MFA_ENROLLMENT_STARTED",actor.userId(),actor.sessionId(),actor.userId(),"SUCCESS",Correlation.current(),now());
        String label=URLEncoder.encode("Moneybags:"+actor.username(),StandardCharsets.UTF_8);
        return new Enrollment(factor,secret,"otpauth://totp/"+label+"?secret="+secret+"&issuer=Moneybags&algorithm=SHA1&digits=6&period=30");
    }
    @Transactional public void confirm(UserPrincipal actor,String factor,Code code){
        var f=db.one("SELECT * FROM M01_IAM_AUTH_FACTOR WHERE FACTOR_ID=? AND USER_ID=? FOR UPDATE",M01IamAuthFactorRow.class,factor,actor.userId()).orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"FACTOR_NOT_FOUND","Factor not found"));
        if(!"PENDING".equals(f.status()))throw new BusinessException(HttpStatus.CONFLICT,"FACTOR_STATE","Factor is not pending verification");
        verify(f,code.code());db.jdbc().update("UPDATE M01_IAM_AUTH_FACTOR SET STATUS='ACTIVE' WHERE FACTOR_ID=?",factor);
        // Existing password-only sessions end; the enrollment session receives fresh MFA strength.
        db.jdbc().update("UPDATE M01_IAM_SESSION SET AUTH_LEVEL=? WHERE SESSION_ID=?",authLevel(),actor.sessionId());
        db.jdbc().update("UPDATE M01_IAM_REFRESH_TOKEN SET STATUS='REVOKED' WHERE STATUS='ACTIVE' AND SESSION_ID IN (SELECT SESSION_ID FROM M01_IAM_SESSION WHERE USER_ID=? AND SESSION_ID<>?)",actor.userId(),actor.sessionId());
        db.jdbc().update("UPDATE M01_IAM_SESSION SET STATUS='POLICY_TERMINATED',ENDED_AT=? WHERE USER_ID=? AND SESSION_ID<>? AND STATUS='ACTIVE'",now(),actor.userId(),actor.sessionId());
        auth.audit("MFA_ENABLED",actor.userId(),actor.sessionId(),actor.userId(),"SUCCESS",Correlation.current(),now());
    }
    public boolean active(String user){return db.count("SELECT COUNT(*) FROM M01_IAM_AUTH_FACTOR WHERE USER_ID=? AND FACTOR_TYPE='TOTP' AND STATUS='ACTIVE'",user)>0;}
    @Transactional(noRollbackFor=BusinessException.class) public void verifyLogin(String user,String code){
        if(!active(user))return;
        if(code==null||code.isBlank())throw new BusinessException(HttpStatus.UNAUTHORIZED,"MFA_REQUIRED","Enter your authenticator code");
        var f=db.one("SELECT * FROM M01_IAM_AUTH_FACTOR WHERE USER_ID=? AND FACTOR_TYPE='TOTP' AND STATUS='ACTIVE' FOR UPDATE",M01IamAuthFactorRow.class,user).orElseThrow();verify(f,code);
    }
    private void verify(M01IamAuthFactorRow factor,String code){
        if(code==null||!code.matches("[0-9]{6}"))invalid();
        String secret=decrypt(factor.factorRef());long step=clock.instant().getEpochSecond()/30;
        for(long slot=step-1;slot<=step+1;slot++){
            if(MessageDigest.isEqual(totp(secret,slot).getBytes(StandardCharsets.US_ASCII),code.getBytes(StandardCharsets.US_ASCII))){
                OffsetDateTime used=OffsetDateTime.ofInstant(Instant.ofEpochSecond(slot*30),ZoneOffset.UTC);
                if(factor.verifiedAt()!=null&&!used.isAfter(factor.verifiedAt()))throw new BusinessException(HttpStatus.UNAUTHORIZED,"MFA_CODE_REUSED","Wait for the next authenticator code; this code was already used");
                db.jdbc().update("UPDATE M01_IAM_AUTH_FACTOR SET VERIFIED_AT=? WHERE FACTOR_ID=?",used,factor.factorId());return;
            }
        }
        invalid();
    }
    private static void invalid(){throw new BusinessException(HttpStatus.UNAUTHORIZED,"MFA_INVALID","Authenticator code is incorrect or expired");}
    @Transactional public void stepUp(UserPrincipal actor,Code code){
        if(!active(actor.userId()))throw new BusinessException(HttpStatus.BAD_REQUEST,"MFA_NOT_ENABLED","Enable an authenticator before step-up");
        verifyLogin(actor.userId(),code.code());db.jdbc().update("UPDATE M01_IAM_SESSION SET AUTH_LEVEL=? WHERE SESSION_ID=? AND STATUS='ACTIVE'",authLevel(),actor.sessionId());
        auth.audit("MFA_STEP_UP",actor.userId(),actor.sessionId(),actor.userId(),"SUCCESS",Correlation.current(),now());
    }
    @Transactional public void revoke(UserPrincipal actor,String factor,FactorRevoke request){
        var credential=auth.credential(actor.userId(),true).orElseThrow();
        if(request.password().getBytes(StandardCharsets.UTF_8).length>72||!passwords.matches(request.password(),credential.passwordHash()))throw new BusinessException(HttpStatus.BAD_REQUEST,"PASSWORD_INCORRECT","Password is incorrect");
        var f=db.one("SELECT * FROM M01_IAM_AUTH_FACTOR WHERE FACTOR_ID=? AND USER_ID=? FOR UPDATE",M01IamAuthFactorRow.class,factor,actor.userId()).orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"FACTOR_NOT_FOUND","Factor not found"));
        if("ACTIVE".equals(f.status()))verify(f,request.code());
        db.jdbc().update("UPDATE M01_IAM_AUTH_FACTOR SET STATUS='REVOKED',REVOKED_AT=? WHERE FACTOR_ID=?",now(),factor);
        db.jdbc().update("UPDATE M01_IAM_SESSION SET AUTH_LEVEL='PASSWORD' WHERE USER_ID=? AND STATUS='ACTIVE'",actor.userId());
        auth.audit("MFA_REVOKED",actor.userId(),actor.sessionId(),actor.userId(),"SUCCESS",Correlation.current(),now());
    }
    public String authLevel(){return "MFA@"+clock.instant().getEpochSecond();}
    public static boolean fresh(String level,Instant instant){
        if(level==null||!level.startsWith("MFA@"))return false;
        try{long age=instant.getEpochSecond()-Long.parseLong(level.substring(4));return age>=0&&age<=300;}catch(NumberFormatException e){return false;}
    }
    private String encrypt(String secret){try{byte[] iv=new byte[12];random.nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));byte[] encrypted=c.doFinal(secret.getBytes(StandardCharsets.US_ASCII));return "T1:"+Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(iv.length+encrypted.length).put(iv).put(encrypted).array());}catch(Exception e){throw new IllegalStateException("MFA encryption failed",e);}}
    private String decrypt(String reference){try{if(!reference.startsWith("T1:"))throw new IllegalArgumentException();byte[] bytes=Base64.getUrlDecoder().decode(reference.substring(3));Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));return new String(c.doFinal(bytes,12,bytes.length-12),StandardCharsets.US_ASCII);}catch(Exception e){throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"MFA_KEY_UNAVAILABLE","Authenticator secret cannot be decrypted; restore the original local MFA key");}}
    public static String base32(byte[] bytes){StringBuilder s=new StringBuilder();int buffer=0,bits=0;for(byte b:bytes){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;s.append(ALPHABET.charAt((buffer>>bits)&31));}}if(bits>0)s.append(ALPHABET.charAt((buffer<<(5-bits))&31));return s.toString();}
    public static String totp(String secret,long step){try{ByteArrayOutputStreamCompat out=new ByteArrayOutputStreamCompat();int buffer=0,bits=0;for(char ch:secret.toCharArray()){int value=ALPHABET.indexOf(ch);if(value<0)throw new IllegalArgumentException();buffer=(buffer<<5)|value;bits+=5;if(bits>=8){bits-=8;out.write((buffer>>bits)&255);}}Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(out.toByteArray(),"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=hash[hash.length-1]&15;int number=((hash[offset]&127)<<24)|((hash[offset+1]&255)<<16)|((hash[offset+2]&255)<<8)|(hash[offset+3]&255);return String.format(Locale.ROOT,"%06d",number%1_000_000);}catch(GeneralSecurityException e){throw new IllegalStateException(e);}}
    private static class ByteArrayOutputStreamCompat extends java.io.ByteArrayOutputStream {}
}
