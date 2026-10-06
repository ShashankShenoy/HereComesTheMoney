package com.moneybags.privacy.casework;

import org.springframework.stereotype.Component;

import java.util.List;

/** Central routing policy that can be replaced by product-specific configuration later. */
@Component
public class CaseRoutingPolicy {
    /** Routes only to owners explicitly identified by verified intake. */
    public List<Route> routes(ComplianceCase.CaseType type, String targetService, List<String> targetServices) {
        var owners = targetServices == null ? List.<String>of() : targetServices;
        if (owners.isEmpty() && targetService != null && !targetService.isBlank()) owners = List.of(targetService);
        return owners.stream().filter(owner -> owner != null && !owner.isBlank()).distinct()
                .map(owner -> new Route(owner, action(type))).toList();
    }

    private String action(ComplianceCase.CaseType type) {
        return switch (type) {
            case ACCESS_REQUEST -> "COLLECT_SUBJECT_DATA";
            case CORRECTION_REQUEST -> "REVIEW_CORRECTION";
            case ERASURE_REQUEST -> "ASSESS_DISPOSITION";
            case RESTRICTION_REQUEST -> "APPLY_PROCESSING_RESTRICTION";
            case RETENTION_REVIEW -> "REVIEW_RETENTION";
            case PRIVILEGED_ACCESS_REVIEW -> "REVIEW_PRIVILEGED_ACCESS";
            case REGULATORY_INQUIRY -> "COLLECT_AUDIT_EVIDENCE";
        };
    }

    public record Route(String service, String actionCode) {}
}
