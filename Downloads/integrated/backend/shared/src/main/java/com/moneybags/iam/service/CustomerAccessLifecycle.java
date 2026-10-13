package com.moneybags.iam.service;

import com.moneybags.common.database.BusinessRepository;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** IAM-owned reaction to CIF closure; credentials and customer history remain separate. */
@Service
public class CustomerAccessLifecycle {

  private final BusinessRepository db;
  private final Clock clock;

  public CustomerAccessLifecycle(BusinessRepository db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Ends linked access atomically; an identity with another active CIF may remain enabled. */
  @Transactional
  public void customerClosed(String cifId) {
    var users = db.rows(
      "SELECT DISTINCT U.USER_ID FROM M01_IAM_USER U JOIN M01_IAM_CUSTOMER_LINK L ON L.USER_ID=U.USER_ID WHERE L.CIF_ID=? AND L.STATUS='ACTIVE' AND U.USER_TYPE='CUSTOMER'",
      cifId
    );
    var now = OffsetDateTime.now(clock);
    db
      .jdbc()
      .update(
        "UPDATE M01_IAM_CUSTOMER_LINK SET STATUS='REVOKED',VALID_TO=? WHERE CIF_ID=? AND STATUS='ACTIVE'",
        now,
        cifId
      );
    for (var user : users) {
      String id = user.get("USER_ID").toString();
      db
        .jdbc()
        .update(
          "UPDATE M01_IAM_SESSION SET STATUS='REVOKED',ENDED_AT=? WHERE USER_ID=? AND STATUS='ACTIVE'",
          now,
          id
        );
      db
        .jdbc()
        .update(
          "UPDATE M01_IAM_REFRESH_TOKEN SET STATUS='REVOKED' WHERE STATUS='ACTIVE' AND SESSION_ID IN (SELECT SESSION_ID FROM M01_IAM_SESSION WHERE USER_ID=?)",
          id
        );
      db
        .jdbc()
        .update(
          "UPDATE M01_IAM_USER SET ENTITLEMENT_VERSION=ENTITLEMENT_VERSION+1,ROW_VERSION=ROW_VERSION+1,UPDATED_AT=? WHERE USER_ID=?",
          now,
          id
        );
      if (
        db.count(
          "SELECT COUNT(*) FROM M01_IAM_CUSTOMER_LINK L JOIN M02_CIF_CUSTOMER C ON C.CIF_ID=L.CIF_ID WHERE L.USER_ID=? AND L.STATUS='ACTIVE' AND C.STATUS='ACTIVE'",
          id
        ) ==
        0
      ) db
        .jdbc()
        .update(
          "UPDATE M01_IAM_USER SET STATUS='DISABLED' WHERE USER_ID=? AND STATUS<>'CLOSED'",
          id
        );
    }
  }
}
