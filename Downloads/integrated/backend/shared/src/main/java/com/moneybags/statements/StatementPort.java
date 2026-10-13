package com.moneybags.statements;

import com.moneybags.iam.security.UserPrincipal;
import com.moneybags.statements.ApiModels.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

/** The only reporting dependency available to banking and its assistant. */
public interface StatementPort {
    List<StatementView> statements(UserPrincipal user, long accountId, int limit);
    List<RequestView> requests(UserPrincipal user, long accountId, int limit);
    RequestView request(UserPrincipal user, String id);
    StatementView statement(UserPrincipal user, String id);
    ResponseEntity<RequestView> create(UserPrincipal user, String key, CreateRequest body);
    RequestView issue(UserPrincipal user, String id);
}
