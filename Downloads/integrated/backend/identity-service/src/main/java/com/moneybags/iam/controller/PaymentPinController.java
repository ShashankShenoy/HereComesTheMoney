package com.moneybags.iam.controller;
import com.moneybags.common.api.ApiResponse;
import com.moneybags.integration.PaymentPinService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/auth/payment-pin")
public class PaymentPinController {
 private final PaymentPinService pins;public PaymentPinController(PaymentPinService pins){this.pins=pins;}
 public record Setup(@NotBlank @Pattern(regexp="[0-9]{6}") String pin){@Override public String toString(){return "Setup[REDACTED]";}}
 public record Reset(@NotBlank @Size(max=72) String currentPassword,@NotBlank @Pattern(regexp="[0-9]{6}") String newPin){@Override public String toString(){return "Reset[REDACTED]";}}
 @GetMapping public ApiResponse<Map<String,Object>> status(){return ApiResponse.of(pins.status());}
 @PostMapping("/setup") public ApiResponse<Map<String,String>> setup(@Valid @RequestBody Setup body){pins.setup(body.pin());return ApiResponse.of(Map.of("status","CONFIGURED"));}
 @PostMapping("/reset") public ApiResponse<Map<String,String>> reset(@Valid @RequestBody Reset body){pins.reset(body.currentPassword(),body.newPin());return ApiResponse.of(Map.of("status","RESET"));}
}
