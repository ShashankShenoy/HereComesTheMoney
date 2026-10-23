package com.moneybags;

import com.moneybags.common.api.BusinessException;
import com.moneybags.iam.security.BankCheckerWriteBoundary;
import com.moneybags.iam.security.UserPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class BankCheckerProductActivationTest {
    private final BankCheckerWriteBoundary boundary = new BankCheckerWriteBoundary();

    @BeforeEach void checker() {
        var user = new UserPrincipal("checker", "checker1", "STAFF", "session", 1L,
            List.of("BANK_CHECKER"), List.of("PRODUCT_READ", "PRODUCT_APPROVE"), List.of());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void permitsIndependentActivationToReachProductAuthorization() {
        assertTrue(allowed("POST", "/api/v1/products/versions/81/activate"));
        assertTrue(allowed("POST", "/api/v1/products/approvals/81/decision"));
    }

    @Test void stillBlocksProductCreationAndRuleChanges() {
        denied("POST", "/api/v1/products/28/versions");
        denied("PUT", "/api/v1/products/versions/81/rules/limits/41");
        denied("POST", "/api/v1/products/versions/81/submit");
    }

    @Test void activationExceptionIsLimitedToTheExactPostRoute() {
        denied("PUT", "/api/v1/products/versions/81/activate");
        denied("DELETE", "/api/v1/products/versions/81/activate");
        denied("POST", "/api/v1/products/versions/81/activate/other");
        denied("POST", "/api/v1/products/28/activate");
    }

    private boolean allowed(String method, String path) {
        return boundary.preHandle(new MockHttpServletRequest(method, path), new MockHttpServletResponse(), new Object());
    }

    private void denied(String method, String path) {
        var error = assertThrows(BusinessException.class, () -> allowed(method, path));
        assertEquals("CHECKER_APPROVAL_ONLY", error.code());
    }
}
