package com.moneybags.iam.dto;
import jakarta.validation.constraints.*;
public record LoginRequest(
    @NotBlank @Size(max=120) String username,
    @NotBlank @Size(max=72) String password,
    @Size(max=80) String clientId,
    @Size(max=160) String deviceRef,
    @Pattern(regexp="[0-9]{6}") String otp
) {
    public LoginRequest(String username,String password,String clientId,String deviceRef) {this(username,password,clientId,deviceRef,null);}
}
