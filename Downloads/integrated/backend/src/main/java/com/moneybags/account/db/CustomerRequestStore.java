package com.moneybags.account.db;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.HttpStatus;
import com.moneybags.account.api.ApiException;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Pending customer requests never alter live limits until an officer applies one. */
@Repository
public class CustomerRequestStore {
    private final JdbcClient db;
    public CustomerRequestStore(JdbcClient db) { this.db = db; }

    public Optional<Map<String,Object>> byRequest(String requestId) {
        return db.sql("SELECT * FROM MBX_ACCOUNT_CUSTOMER_REQUEST WHERE REQUEST_ID=:r")
                .param("r",requestId).query().listOfRows().stream().findFirst();
    }
    public Map<String,Object> lock(String requestId) {
        return db.sql("SELECT * FROM MBX_ACCOUNT_CUSTOMER_REQUEST WHERE REQUEST_ID=:r FOR UPDATE")
                .param("r",requestId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"REQUEST_NOT_FOUND","Account request not found"));
    }
    public List<Map<String,Object>> mine(long accountId,String userId) {
        return db.sql("SELECT * FROM MBX_ACCOUNT_CUSTOMER_REQUEST WHERE ACCOUNT_ID=:a AND REQUESTED_BY_USER_ID=:u ORDER BY REQUESTED_AT DESC FETCH FIRST 100 ROWS ONLY")
                .param("a",accountId).param("u",userId).query().listOfRows();
    }
    public List<Map<String,Object>> pending() {
        return db.sql("SELECT * FROM MBX_ACCOUNT_CUSTOMER_REQUEST WHERE REQUEST_STATUS='PENDING' ORDER BY REQUESTED_AT FETCH FIRST 100 ROWS ONLY")
                .query().listOfRows();
    }
    public void insert(long accountId,String requestId,String type,String operation,String period,
                       BigDecimal amount,String details,String userId) {
        db.sql("INSERT INTO MBX_ACCOUNT_CUSTOMER_REQUEST (ACCOUNT_ID,REQUEST_ID,REQUEST_TYPE,OPERATION_CODE,PERIOD_CODE,REQUESTED_AMOUNT,DETAILS,REQUESTED_BY_USER_ID) VALUES (:a,:r,:t,:o,:p,:v,:d,:u)")
                .param("a",accountId).param("r",requestId).param("t",type)
                .param("o",operation).param("p",period).param("v",amount)
                .param("d",details).param("u",userId).update();
    }
    public Long appliedLimitId(String requestId) {
        return db.sql("SELECT LIMIT_ID FROM M04_ACCOUNT_LIMIT WHERE CHANGE_REQUEST_ID=:r")
                .param("r",requestId).query(Long.class).optional().orElse(null);
    }
    public void decide(String requestId,String decision,String reason,Long limitId,String userId) {
        db.sql("UPDATE MBX_ACCOUNT_CUSTOMER_REQUEST SET REQUEST_STATUS=:s,DECISION_REASON=:r,APPLIED_LIMIT_ID=:l,DECIDED_BY_USER_ID=:u,DECIDED_AT=SYSTIMESTAMP WHERE REQUEST_ID=:id AND REQUEST_STATUS='PENDING'")
                .param("s",decision).param("r",reason).param("l",limitId)
                .param("u",userId).param("id",requestId).update();
    }
}
