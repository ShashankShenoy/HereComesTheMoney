package com.moneybags.common.api;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
public class ApiDocumentationController {
 @GetMapping("/api/v1/docs/openapi") public ResponseEntity<Void> openapi(){return ResponseEntity.status(302).location(java.net.URI.create("/v3/api-docs")).build();}
}
