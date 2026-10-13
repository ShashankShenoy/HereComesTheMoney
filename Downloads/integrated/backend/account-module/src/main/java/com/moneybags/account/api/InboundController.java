package com.moneybags.account.api;

import com.moneybags.account.api.Models.*;
import com.moneybags.account.domain.InboundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated event-ingestion endpoints used by the Module 5 adapter. */
@RestController
@RequestMapping("/api/v1/internal/events")
@Tag(name = "Module 5 event ingestion")
public class InboundController {
    private final InboundService service;
    public InboundController(InboundService service) { this.service = service; }
    /** Applies a control acknowledgement, including fence version and source event ID. */
    @PostMapping("/control-acks")
    @Operation(summary = "Consume a Module 5 control acknowledgement")
    public ResponseEntity<Void> control(@Valid @RequestBody ControlAck event) {
        service.controlAck(event); return ResponseEntity.noContent().build();
    }
    /** Applies one ordered ledger, hold, lien, or overdraft projection event. */
    @PostMapping("/financial-projections")
    @Operation(summary = "Consume an ordered Module 5 financial event")
    public ResponseEntity<Void> projection(@Valid @RequestBody ProjectionEvent event) {
        service.projection(event); return ResponseEntity.noContent().build();
    }
}
