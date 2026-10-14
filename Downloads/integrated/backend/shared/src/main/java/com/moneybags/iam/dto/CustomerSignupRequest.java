package com.moneybags.iam.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record CustomerSignupRequest(
    @NotBlank @Pattern(regexp="[A-Za-z0-9._@-]{3,120}") String username,
    @NotBlank @Size(max=300) String legalName,
    @NotNull @Past LocalDate dateOfBirth,
    @NotBlank @Size(min=10,max=72) String password,
    @Pattern(regexp="[0-9]{6}") String paymentPin
) {
 public CustomerSignupRequest(String username,String legalName,LocalDate dateOfBirth,String password){this(username,legalName,dateOfBirth,password,null);}
 @Override public String toString(){return "CustomerSignupRequest[credentials REDACTED]";}
}
