package com.moneybags.common.security;

import static com.moneybags.iam.repository.IamRepository.map;

import com.moneybags.common.api.Correlation;
import com.moneybags.common.database.*;
import com.moneybags.iam.security.UserPrincipal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Rejected domain requests retain evidence even when the business transaction rolls back. */
@Service
public class DomainDeniedAudit {

  private final BusinessRepository db;

  public DomainDeniedAudit(BusinessRepository db) {
    this.db = db;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(UserPrincipal user, String path, int status) {
    var values = map(
      "AUDIT_ID",
      UUID.randomUUID().toString(),
      "EVENT_TYPE",
      "DOMAIN_REQUEST_REJECTED",
      "ACTOR_USER_ID",
      user.userId(),
      "SESSION_ID",
      user.sessionId(),
      "RESULT",
      status < 500 ? "DENIED" : "FAILED",
      "REASON_CODE",
      "HTTP_" + status,
      "CORRELATION_ID",
      Correlation.current()
    );
    if (path.startsWith("/api/v1/cif/")) db.insert(
      SchemaTable.M02_CIF_AUDIT_EVENT,
      values
    );
    else {
      values.put("RESOURCE_TYPE", "HTTP_ROUTE");
      values.put("RESOURCE_ID", path.substring(0, Math.min(80, path.length())));
      db.insert(SchemaTable.M03_PM_AUDIT_EVENT, values);
    }
  }
}
