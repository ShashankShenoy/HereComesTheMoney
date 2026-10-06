package com.moneybags.iam.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record CustomerSignupRequest(
    @NotBlank @Pattern(regexp="[A-Za-z0-9._@-]{3,120}") String username,
    @NotBlank @Size(max=300) String legalName,
    @NotNull @Past LocalDate dateOfBirth,
    @NotBlank @Size(min=10,max=72) String password
) {}
