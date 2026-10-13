package com.moneybags.integration;
import com.moneybags.iam.service.IamAdminService;
import com.moneybags.iam.dto.IamDtos.CreateUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Valid;
import java.util.*;
@RestController
@RequestMapping("/api/v1/customer-access")
public class CustomerHashController {
 private final CustomerHashService hashes;private final IamAdminService iam;
 public CustomerHashController(CustomerHashService hashes,IamAdminService iam){this.hashes=hashes;this.iam=iam;}
 @PostMapping("/users") @Transactional
 public Map<String,Object> create(@Valid @RequestBody CreateUser body){
  var user=iam.createUser(CurrentActor.get(),body);
  if("CUSTOMER".equals(user.userType()))return Map.of("user",user,"customerHash",hashes.issue(user.userId()));
  return Map.of("user",user);
 }
 @PostMapping("/rotate") @Transactional
 public Map<String,String> rotate(){
  if(!"CUSTOMER".equals(CurrentActor.get().userType()))throw new com.moneybags.common.api.BusinessException(org.springframework.http.HttpStatus.FORBIDDEN,"CUSTOMER_ONLY","Only customers can rotate their own hash");
  String hash=hashes.issue(CurrentActor.get().userId());hashes.audit("CUSTOMER_HASH_ROTATED","USER",CurrentActor.get().userId(),"SELF_SERVICE");
  return Map.of("customerHash",hash);
 }
}
