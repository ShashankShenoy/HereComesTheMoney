package com.moneybags.account.api;

import com.moneybags.account.domain.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Narrow account facts for authenticated peer services such as payments and loans. */
@RestController
@RequestMapping("/api/v1/internal/accounts")
@Tag(name = "Peer account lookup")
public class PeerAccountController {
    private final AccountService service;
    public PeerAccountController(AccountService service) { this.service = service; }
    /** Exposes account identity and control version, never spend authorization. */
    @GetMapping("/{id}/context")
    @Operation(summary = "Read account context for a peer module")
    public Map<String, Object> context(@PathVariable long id) { return service.peerContext(id); }
    /** Exposes current holder/signatory roles without nominee personal information. */
    @GetMapping("/{id}/parties")
    @Operation(summary = "Read current account parties for a peer module")
    public List<Map<String, Object>> parties(@PathVariable long id) { return service.peerParties(id); }
}
