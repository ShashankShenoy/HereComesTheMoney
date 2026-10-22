package com.moneybags.integration;

import com.moneybags.common.api.BusinessException;
import com.moneybags.common.database.BusinessRepository;
import org.springframework.http.HttpStatus;

/** Resolve the deposit account liability used by loan disbursements and repayments. */
final class LoanAccountLiabilityGl {
    private LoanAccountLiabilityGl() {}

    static long resolve(BusinessRepository db, long accountProductVersionId) {
        var mappings = db.rows("""
            SELECT M.GL_ACCOUNT_ID
            FROM M05_GL_PRODUCT_MAPPING M
            JOIN M05_GL_ACCOUNT G ON G.GL_ACCOUNT_ID=M.GL_ACCOUNT_ID
            WHERE M.PRODUCT_VERSION_ID=? AND M.GL_ROLE_CODE='CUSTOMER_LIABILITY'
              AND M.POSTING_TYPE IN ('LOAN','DEPOSIT') AND M.STATUS='ACTIVE'
              AND M.EFFECTIVE_FROM<=SYSDATE
              AND (M.EFFECTIVE_TO IS NULL OR M.EFFECTIVE_TO>=SYSDATE)
              AND G.ACCOUNT_CLASS='LIABILITY' AND G.ACTIVE_FLAG='Y'
            ORDER BY CASE M.POSTING_TYPE WHEN 'LOAN' THEN 0 ELSE 1 END,
                     M.EFFECTIVE_FROM DESC
            FETCH FIRST 1 ROW ONLY
            """, accountProductVersionId);
        if (mappings.isEmpty()) throw new BusinessException(HttpStatus.CONFLICT, "GL_MAPPING_MISSING",
            "Account product version has no active customer liability GL mapping for loan posting");
        return ((Number) mappings.get(0).get("GL_ACCOUNT_ID")).longValue();
    }
}
