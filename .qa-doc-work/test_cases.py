GROUPS = []

def group(code, title, location, source):
    GROUPS.append(dict(code=code, title=title, location=location, source=source, cases=[]))

def case(n, title, pre, steps, expected, priority='P1', mode='UI and API'):
    g = GROUPS[-1]
    g['cases'].append(dict(id=f"{g['code']}-{n:02d}", title=title, pre=pre,
                          steps=steps.split(' || '), expected=expected, priority=priority, mode=mode))

group('M1-AUTH', 'Sign in and customer registration', 'Customer sign in and Employee sign in', 'S1 S3 S4 S8')
case(1, 'Valid sign in and workspace routing', 'Active customer C1 and employee E1 without MFA; separate browser profiles.',
     'Open /login and sign in as C1. || Sign out; open /employee/login and sign in as E1. || Repeat each identity from the opposite entry page and refresh the resulting workspace.',
     'Authentication succeeds once per submission. The authenticated user type determines the workspace and available navigation. A switched entry gives the routing notice. /auth/me identifies the correct user; no prior user data remains.', 'P0')
case(2, 'Required inputs and failed login recovery', 'Signed out; known active account and nonexistent username.',
     'Submit blank username or password; then whitespace-only username. || Try the known username with an incorrect password and the unknown username. || Correct the credentials and submit again; double-click during a delayed response.',
     'Required fields block empty UI submissions. Invalid credentials receive a safe generic rejection without revealing which account exists. No session is issued. Error is visible; controls recover; the pending button prevents duplicate UI submissions.')
case(3, 'Inactive user states deny authentication', 'Disposable identities in PENDING_ACTIVATION, AUTH_LOCKED, SUSPENDED, DISABLED and CLOSED.',
     'For every state, attempt login using the correct password. || For a suspended identity retain a token obtained before suspension and call /auth/me. || Activate a permitted nonterminal identity and retry login.',
     'Every listed inactive state rejects login. The old token fails after deactivation. A permitted reactivation restores login; CLOSED remains terminal. Do not confuse administrative AUTH_LOCKED status with timed credential lockout.', 'P0')
case(4, 'Failed attempt threshold and timed lockout', 'Disposable ACTIVE identity; record configured max attempts N and lock duration L. Defaults are 5 and 15 minutes.',
     'Fail login N minus 1 times, then once more. || During L, submit a correct password. || Advance the isolated test clock beyond expiry or wait; use the correct password. || Repeat with successful login before the threshold.',
     'At threshold, credential lockout persists even though the request fails. Correct credentials are denied during the lock. Successful login after expiry resets the counter. A successful earlier login also clears failures. Failure audit survives rejection.', 'P0')
case(5, 'Authenticator challenge and replay at sign in', 'C1 has an ACTIVE TOTP factor; retain unused valid and already-used test codes.',
     'Use correct password with no code, malformed code, expired code and a wrong six-digit code. || Use a valid unused code; sign out and reuse it. || Wait for the next code and sign in.',
     'Missing code produces MFA_REQUIRED and opens/focuses the authenticator input. Invalid or stale codes fail; replay produces MFA_CODE_REUSED. A fresh valid code succeeds. Missing MFA does not increment credential failures; invalid/reused codes do.', 'P0')
case(6, 'Sign up availability and back navigation', 'Two isolated configurations with customer signup enabled and disabled; simulated availability endpoint outage.',
     'Open customer sign in in each configuration; inspect availability text and Create a customer account. || In enabled mode open signup, enter partial data, then Back to sign in. || Test an availability failure and call signup directly while disabled.',
     'The enabled flag alone exposes signup. Disabled mode and direct API reject registration with SIGNUP_DISABLED. Outage shows an unavailable message. Back returns to sign in without creating records. Employee sign in does not offer public signup.')
case(7, 'Adult demo signup creates linked identity atomically', 'Signup enabled; unique username and legal name; age at least 18.',
     'Fill all signup fields with matching passwords and submit. || Inspect the signed-in identity, linked CIF, customer role and own banking entry. || Check registration records and audit through authorized inspection.',
     'One ACTIVE CUSTOMER, verified demo CIF, SELF link and scoped customer assignment are created together. The browser signs in and routes to personal banking. Audit identifies DEMO_CUSTOMER_SIGNUP and DEMO_KYC_ASSUMED. This is the explicit demo bypass, not evidence of full KYC execution.', 'P0')
case(8, 'Signup age and field boundaries', 'Signup enabled; independent username for each accepted variant; boundary matrix in appendix.',
     'Try exactly 18 today, one day below 18, future birth date and leap-day birthday variants. || Try mismatched passwords, duplicate username with changed case, invalid username characters, blank name and limits minus one, at limit and over limit. || Correct a failed form and resubmit.',
     'Exact adulthood is accepted by date arithmetic; underage applicants receive GUARDIAN_REQUIRED. Required/format/length failures are rejected; duplicate usernames cannot create another identity. Password mismatch is caught in the UI. Rejection leaves no partial CIF, link, role assignment or credential.')
case(9, 'Signup permissions and partial failure rollback', 'Isolated seeded database with controllable signup role and setup dependencies.',
     'Add a staff-only permission to RETAIL_CUSTOMER and register a disposable customer. || Repeat with a contaminated dedicated signup role, inactive role, missing required permission or missing bootstrap administrator role. || Inspect all related tables after each failed attempt.',
     'A safe dedicated customer role is used when possible. Unsafe or missing setup fails with SETUP_REQUIRED; public signup never inherits staff privileges. A failed operation rolls back all identity, CIF, link and assignment records.', 'P0', 'API and database')

group('M1-OVR', 'Identity and access overview', 'Administration > Overview', 'S1 S5')
case(1, 'Overview counters and shortcuts', 'IAM_USER_READ; known users, active roles, submitted requests and live/expired sessions.',
     'Open Overview and compare all four cards with API counts. || Open each Administrative areas shortcut. || Open a recent access request and View all; return with navigation.',
     'Total identities, active identities, active roles, pending requests and active sessions match their definitions. Expired sessions are excluded from the live count. Recent requests show at most five; View all opens Access requests; each shortcut opens its named page.')
case(2, 'One time independent checker setup', 'Fresh isolated fixture with no effective global checker and no approved access request; authorized administrator.',
     'Use Set up checker and create an EMPLOYEE with unique username, employee reference and valid password. || Sign in separately as the checker; inspect role and available decisions. || Refresh Overview; repeat setup through UI/API and issue two simultaneous setup attempts in a fresh fixture.',
     'Exactly one ACTIVE checker receives the reserved global checker role. Setup disappears and later attempts receive SETUP_COMPLETE. Nonemployee or incomplete input fails. Concurrent setup cannot bootstrap two checkers. Later access changes use independent approval.', 'P0')
case(3, 'Overview load failure and least privilege', 'User without IAM_USER_READ; controlled dashboard or catalog outage.',
     'Attempt direct Overview route and /iam/dashboard without read permission. || With read permission fail one data request, observe Retry, restore service and retry. || Navigate away before a delayed result completes.',
     'Unauthorized API access is denied. Failure shows a recoverable error instead of misleading zero counts. Retry restores correct data. An old render cannot replace the newer page.', 'P1')

group('M1-USR', 'Users and user detail', 'Administration > Users > View user', 'S1 S5 S8')
case(1, 'User directory search filters and pagination', 'More than 15 users spanning all six directory statuses.',
     'Search by full/partial username, case variants, no match and blank reset. || Apply each status and combined search/status; navigate Next and Previous. || Change a filter from a later page; request invalid API page/size values.',
     'Results reflect both filters and show correct total/page. UI page size is 15; first Previous and last Next are disabled. Filtering resets to page one. Empty result is explicit. API rejects negative page or size outside 1 through 100.')
case(2, 'Create employee and service identities', 'IAM_USER_CREATE; signup flag recorded; unique usernames.',
     'Create EMPLOYEE with employee reference and valid password. || Switch user type to SERVICE and inspect the reference field before saving. || Open both users; attempt sign in before activation and inspect audit.',
     'Employee reference is required only for EMPLOYEE; switching to SERVICE clears and disables it. Both normal identities start PENDING_ACTIVATION with no implicit grant. Login fails until activated; persisted responses contain no password/hash.')
case(3, 'Create customer in standard and demo modes', 'IAM_USER_CREATE; one fixture with signup disabled and one enabled.',
     'With signup disabled create CUSTOMER and capture the one-time holder key securely. || With signup enabled choose CUSTOMER, enter legal name and birth date and save. || Switch CUSTOMER to EMPLOYEE and back before submission to check conditional fields.',
     'Standard creation gives a pending identity and one-time key without inventing a verified CIF. Demo creation gives an ACTIVE linked customer with assumed verified KYC. Legal-name/DOB fields apply only to the demo customer path; hidden values are cleared and not submitted.')
case(4, 'User creation validation and duplicates', 'Create user dialog; disposable data from boundary matrix.',
     'Submit missing employee reference, a reference on nonemployee via API, invalid username characters and 2/3/120/121-character usernames. || Test passwords at 9/10 characters and 72/73 UTF-8 bytes, including multibyte text. || Create a duplicate username with case changes; send simultaneous duplicate requests.',
     'All contract violations are rejected without creating user/credential remnants. Password policy uses UTF-8 bytes at the upper bound. Case-insensitive uniqueness holds under concurrency. Errors preserve editable dialog values.')
case(5, 'User detail record and controls', 'Read-only IAM identity plus administrator; a user with assignments, dimensions, links and sessions.',
     'Open View user and compare identity fields, entitlement version, role scopes/validity, customer links and sessions with API data. || Open as read-only user; inspect all action buttons. || Close by X and reopen a different user.',
     'Every panel belongs to the selected identity; no stale child record persists. Read access alone exposes no mutation controls. Manage, access-request and session controls appear only with their corresponding permissions; direct unauthorized calls fail.')
case(6, 'Identity lifecycle permitted transitions', 'Fresh disposable identity for each row in the identity transition matrix.',
     'Use Change status for every permitted transition; enter a reason and save. || After each transition reload the user, test login and inspect prior sessions. || Exercise invalid transitions and same-state requests directly.',
     'Only matrix transitions succeed, with row version and audit update. Leaving ACTIVE ends sessions and refresh tokens. Reactivation clears failed attempts. CLOSED cannot reactivate; unsupported transitions fail without changing records.', 'P0')
case(7, 'Identity status self protection and stale edit', 'Two administrators viewing the same user; own admin profile.',
     'Try suspending, disabling, locking and closing your own administrator identity. || Open two status dialogs for another user; save one then submit the stale version. || Reload and submit a currently permitted transition.',
     'Self-lockout changes fail. The stale request returns VERSION_CONFLICT and preserves the newer state. Reloading supplies the new version; a valid transition succeeds. No stale change silently overwrites current status.', 'P0')
case(8, 'Administrative password reset', 'IAM_USER_MANAGE; target active in two browser sessions and another target SUSPENDED.',
     'Open Reset password; test missing reason and invalid new password, then save a valid reset. || Try old password, new password and both old session/refresh tokens. || Reset suspended user and repeat; reset your own password last.',
     'Valid reset ends every target session and rotates stored credentials. Old password/tokens fail. Reset does not activate SUSPENDED or DISABLED identities. Self reset returns the current browser to sign in. No password appears in administrative responses or audit.', 'P0')
case(9, 'Lost MFA reset and administrative session revoke', 'Target with ACTIVE and PENDING factors plus multiple sessions; another administrator.',
     'Use Reset lost MFA with reason; inspect factors and login/session behavior. || Attempt self reset and unauthorized reset by API. || Recreate two sessions and revoke one from View user; try a session ID belonging to another target.',
     'Reset revokes all nonrevoked factors and ends all target sessions; new enrollment is possible after sign in. Self reset fails. Session revoke ends only the addressed target session; a mismatched user/session pair is rejected.', 'P0')
case(10, 'Link existing active CIF to customer identity', 'IAM_USER_MANAGE; unlinked CUSTOMER and ACTIVE verified CIF A.',
     'Use Link existing CIF, enter A and relationship SELF; save. || Refresh My security as the customer and check linked CIFs. || Attempt a duplicate active link, an employee/service target, missing CIF and nonactive CIF.',
     'Valid link persists and increments entitlement version. Only CUSTOMER identities can link to an existing ACTIVE CIF. Duplicate/invalid links fail. A digital link alone does not grant account operating authority; SELF permission still needs an effective assignment.', 'P0')
case(11, 'Revoke link and immediate scope reevaluation', 'Customer linked to two CIFs with SELF access; account fixture for each.',
     'Cancel Revoke link once, then confirm for CIF A. || Without signing out, call /auth/me, own CIF reads and account reads for A and B. || Repeat revocation and try another user link ID.',
     'Cancel changes nothing. Confirm preserves history as REVOKED and removes A from effective links on subsequent requests. B remains available where authorized. Link revocation does not close CIF or accounts. Repeated/mismatched revocation cannot affect another link.', 'P0')

group('M1-ROLE', 'Roles permissions and approval authority', 'Administration > Roles & permissions', 'S1 S5 S6')
case(1, 'Role and permission catalogues', 'IAM_USER_READ; roles with standard/sensitive flags and permissions with/without step-up.',
     'Open both catalogues; compare code, display name, sensitivity, status, resource/action and step-up indicators. || View a role and inspect permissions and authority rows. || Repeat without IAM_ROLE_MANAGE.',
     'Role contents and authority currency/amount/rate/validity are accurate. Read-only users can inspect but cannot create/edit/end authority. Empty catalogues and authorities show explicit empty states.')
case(2, 'Create and edit a custom role', 'IAM_ROLE_MANAGE; existing test permissions and a target assigned a custom role.',
     'Create a standard role with selected permissions; create a sensitive variant. || Edit display name, sensitivity and permission selections. || Check target authorization on its next request without re-login; then retire the role.',
     'Saved role exactly matches selections. Updates bump affected users entitlements and change live authorization. Retiring removes effective permissions from subsequent requests; historical assignments remain visible. Role code is immutable in edit UI.', 'P0')
case(3, 'Role validation protection and concurrent edit', 'Two admin sessions and custom role; protected BANK_ADMIN/BANK_CHECKER.',
     'Try invalid/duplicate role code, blank/oversized display name, unknown/duplicate permission IDs and more than 100 IDs. || Attempt creating reserved codes and editing/retiring reserved roles through API. || Save two edits using the same rowVersion.',
     'Invalid role definitions fail atomically. Reserved bank administration roles cannot be created, edited or retired through these paths. Only one concurrent versioned edit succeeds; the stale request returns VERSION_CONFLICT.', 'P0')
case(4, 'Create permission and enforce fresh MFA', 'IAM_ROLE_MANAGE; test code QA_APPROVE and role target without fresh MFA.',
     'Create QA_APPROVE with resource/action and Require fresh MFA set to Yes. || Add to a test role and approve a target assignment. || Check target authorization before and after step-up. || Inspect administrator entitlements.',
     'Permission appears once with MFA REQUIRED. Custom targets need role assignment plus fresh MFA. Creating a permission also adds it to BANK_ADMIN and bumps relevant entitlements, as implemented; test this explicit administrator behavior.', 'P0')
case(5, 'Permission contract and reserved namespaces', 'Permission creation available.',
     'Try codes with lowercase, invalid symbols, too-short/long values and duplicate code. || Submit IAM_ and SYSTEM_ prefixes, blank resource/action and over-limit values. || Set step-up false for a separate valid code.',
     'Validation/uniqueness rejects bad inputs; reserved namespaces remain protected. A valid non-step-up permission shows Not required. Failed creation does not partially grant the permission to any role.')
case(6, 'Add authority and numeric boundaries', 'Custom role; authority QA_APPROVE; current validity window.',
     'Add INR maximum amount 100000.0000 and maximum rate 12.50000000. || Evaluate amount/rate just below, equal to and above each bound. || Repeat with amount-only, rate-only and wildcard currency authorities.',
     'Limits are inclusive. All supplied amount/rate checks must fit one authority row on the same scoped assignment. Missing matching currency denies unless wildcard applies. Amount-only cannot satisfy a requested rate lacking a limit and vice versa.', 'P0')
case(7, 'Authority validation validity and end action', 'Role with current, future and expired authorities; other role with separate authority.',
     'Submit both limits blank, negative values, excessive precision, invalid currency and end at/before start. || Check authorization before start, at start, before end and at end. || Cancel then confirm End for current and future authority; try a mismatched role/authority ID.',
     'Invalid definitions fail. Validity is start-inclusive and end-exclusive. Ending current authority expires it; ending future authority removes that future row in the implementation. Permission remains but limit-based decisions cease. Another role authority cannot be ended accidentally.', 'P0')

group('M1-ACC', 'Access requests and independent approval', 'Administration > Access requests and Users > Request access', 'S1 S5 S6')
case(1, 'Submit scoped access grant', 'Maker E1 with IAM_ACCESS_MANAGE; ACTIVE target E3 and ACTIVE custom role.',
     'Open Request access from the queue, then from the target user. || Choose BRANCH B1, valid dates and reason; add SAVINGS,CURRENT and INR,USD dimensions. || Submit and open Review details before approval.',
     'Exactly one SUBMITTED request records target, maker, role, scope, dimensions, validity and reason. User-origin launch preselects the target. Submitted access is not effective before approval. Dimensions are trimmed and uppercased by the UI.', 'P0')
case(2, 'Scope fields and contract validation', 'Maker and active targets of CUSTOMER/EMPLOYEE types.',
     'Switch GLOBAL, BRANCH, REGION and SELF; inspect scope-reference required/disabled state. || Try blank branch/region ref, ref on GLOBAL/SELF, SELF for EMPLOYEE, invalid dates, expired validity, blank reason and duplicate dimensions. || Try retired role and CLOSED target through API.',
     'Reference is mandatory only for branch/region and cleared for global/self. SELF is reserved for customers. Invalid inputs fail without creating a request. UI lists ACTIVE users/roles; API approval also requires ACTIVE target and role.')
case(3, 'Approve grant and propagate effective access', 'SUBMITTED request; independent checker E2, distinct from maker and target.',
     'Review all request fields as E2 and select Approve request. || Refresh the queue, user assignments, Overview counters and target /auth/me. || Test an allowed and out-of-scope action with an existing target session.',
     'Request becomes APPROVED with checker and decision time. One assignment carries exact scope, dimensions and validity. Entitlement version changes; subsequent requests use current permissions. In-scope access works only once validFrom is reached; out-of-scope access remains denied.', 'P0')
case(4, 'Reject request without changing entitlements', 'Separate SUBMITTED grant and revocation requests; independent checker.',
     'Open each request and select Reject request. || Inspect request status/history, target assignments and effective authorization. || Reopen the decided request and attempt another decision through API.',
     'Both become REJECTED with maker/checker evidence. Rejected grant adds no access; rejected revocation leaves existing access effective. Decision controls disappear and a second decision fails.', 'P0')
case(5, 'Maker target and missing permission cannot decide', 'SUBMITTED request; maker, target, unauthorized observer and valid checker.',
     'Open the request under each identity and inspect approval/rejection controls. || Send APPROVED and REJECTED directly as maker and target. || Send a decision without IAM_ACCESS_APPROVE; finish as the valid checker.',
     'Maker and target cannot approve or reject their own change even if they hold approval permission. Backend returns MAKER_CHECKER_SEPARATION or SELF_APPROVAL as appropriate. Missing permission is denied. Only the independent authorized checker succeeds.', 'P0')
case(6, 'Request revocation of exact assignment', 'Active assignment with branch, dates and dimensions; maker and independent checker.',
     'From View user choose Request revoke; review captured assignment and add reason. || Verify access while request is pending, then approve. || Submit tampered variants for assignment ID, user, role, scope, dates and dimensions.',
     'Pending revocation leaves access effective. Approval revokes only the exact active assignment and bumps entitlement version. Every mismatched revocation is rejected. Historical assignment/request data remains; no unrelated role is removed.', 'P0')
case(7, 'Expiry and state changes during review', 'Submitted requests ending soon, targeting a user to suspend, and a role to retire.',
     'Decide an expired request at validTo and just after. || Suspend target or retire role between submission and attempted approval. || Restore a permitted state and reload before deciding a still-valid request.',
     'Expired request persists as EXPIRED with REQUEST_EXPIRED and no grant. Inactive user/role prevents approval. Review uses current state, not values cached when submitted. Failed approval creates no assignment.', 'P0')
case(8, 'Overlapping grants and concurrent decisions', 'Two overlapping requests for same target, role and primary scope; independent reviewers.',
     'Approve first grant, then overlapping second; test adjacent nonoverlapping validity windows separately. || Send simultaneous approve/approve and approve/reject for one request with identical rowVersion. || Retry stale decision.',
     'Overlapping active assignments are rejected with OVERLAPPING_ACCESS even if dimensions differ. Adjacent windows can pass. At most one decision/assignment commits per request; stale or already-decided versions fail without duplicate entitlements.', 'P0')
case(9, 'Queue completeness and large user catalogue', 'More than 200 requests and more than 100 users, including a target outside the first catalogue fetch.',
     'Verify latest-200 ordering, labels, status, scope and View details. || Attempt Request access to the target outside the first 100 users, both from queue and its Users detail. || Record whether the target can be selected correctly.',
     'Queue cap is explicit and detail IDs remain correct. Catalog loading must not cause grants to the wrong target. If a valid target cannot be selected because only 100 users are loaded, record a UI coverage defect; do not count the inaccessible flow as passed.', 'P2')

group('M1-SEC', 'My security and authorization checks', 'Administration or Explore & manage > My security', 'S1 S3 S4 S5 S6')
case(1, 'Identity refresh and refresh token rotation', 'Active identity with a live session; entitlement change performed by another admin.',
     'Inspect username, user type, roles, linked CIFs and permission tags. || Click Refresh session after the change. || Reuse the old refresh token via a controlled API client; try the current access and newest refresh token afterwards.',
     'Refresh reloads identity/menu data and rotates the refresh token. Reusing the old token returns REFRESH_REUSE and revokes that session. Session-ended handling returns to sign in and clears local identity/key state.', 'P0')
case(2, 'Change own password', 'Two sessions for a disposable identity.',
     'Submit wrong current password, unchanged new password and invalid length/byte variants. || Submit correct current and valid different new password. || Test both old sessions and refresh tokens, old password and new password.',
     'Invalid attempts leave password unchanged and show field/form errors. Valid change terminates all sessions including current and requires sign in. Old credentials/tokens fail; new password works. No plaintext is logged or exposed.', 'P0')
case(3, 'Enroll and confirm authenticator', 'Identity with no ACTIVE factor; test authenticator controlled by tester.',
     'Select Set up authenticator; import the displayed secret/URI securely. || Attempt malformed/wrong code then confirm an unused current six-digit code. || Reload factors and reenter setup; test another user factor ID by API.',
     'Factor starts PENDING and becomes ACTIVE only on valid confirmation. Confirmation upgrades this session to fresh MFA and ends other sessions/refresh tokens. Secret/URI appear only in enrollment response, never factor listing. Existing active factor blocks second enrollment. Factor ownership is enforced. Subsequent sign in requires MFA.', 'P0')
case(4, 'Abandoned enrollment and one time code use', 'Identity with no active MFA; two browser sessions.',
     'Start enrollment then cancel. Start again and inspect pending factors. || Confirm one factor; try re-confirming and replaying its used code for login/step-up. || Obtain a fresh code and complete the permitted action.',
     'Cancel does not enable MFA. Reenrollment handles pending factors without enabling multiple authenticators. Confirming nonpending factors fails. Used codes cannot authenticate again; a fresh valid code succeeds. Capture status/audit evidence without retaining the secret.')
case(5, 'Fresh MFA step up and exact expiry', 'ACTIVE factor; permission requiring fresh MFA; controlled clock or timed run.',
     'Attempt protected authorization without freshness, then Verify step-up using an unused code. || Recheck at ages 299, 300 and 301 seconds. || Test step-up with no active factor, invalid code and another session.',
     'Fresh step-up enables protected checks in the current session. The implementation accepts age 0 through 300 seconds inclusive and denies at 301. No factor returns MFA_NOT_ENABLED; invalid/reused code fails. Freshness does not transfer to unrelated sessions.', 'P0')
case(6, 'Remove authenticator safely', 'ACTIVE TOTP factor and current password.',
     'Open Remove authenticator; cancel once. || Try wrong password, blank/wrong/reused code, then correct password plus fresh code. || Inspect factors and perform a new sign in; try another user factor ID.',
     'Cancel and failed validation keep factor active. Successful removal revokes it and downgrades session MFA level; future sign in uses password unless another active factor exists. Unauthorized factor removal fails. A revoked factor cannot continue generating valid authentication.', 'P0')
case(7, 'View and revoke own sessions', 'Same user signed in through two independent sessions plus another user session.',
     'Compare device/client, current marker, activity, idle expiry and status. || Cancel then revoke other own session; exercise its token. || Revoke current session; test foreign session ID directly.',
     'Other own session becomes unusable while current remains. Current-session revoke signs out immediately. Foreign session revocation is forbidden. Session list and markers refresh accurately; ended sessions offer no live Revoke action.', 'P0')
case(8, 'Idle and absolute session expiry', 'Record configured idle/absolute limits; defaults 30 minutes and 8 hours; isolated test clock.',
     'Call an authenticated endpoint just before and at idle expiry without intervening activity. || In a separate session keep activity before idle timeout until absolute expiry. || Try refresh at expired boundaries and browser refresh on an expired session.',
     'Idle and absolute expiry are enforced at the boundary; activity cannot extend absolute lifetime. Expired sessions/refresh tokens fail, server status becomes EXPIRED and UI signs out without exposing a stale protected page.', 'P0')
case(9, 'Authorization scope and dimensions matrix', 'Roles covering GLOBAL, B1 BRANCH, R1 REGION and linked-CIF SELF; dimensioned test assignment.',
     'Use Check my authorization with matching, missing and mismatched branch/region/CIF contexts. || Try SAVINGS or CURRENT with INR or USD, then mismatched product/currency. || Try unknown permission and unlinked customer CIF.',
     'Only a currently effective assignment covering permission and all context dimensions allows. Same-dimension values are alternatives; different dimensions all must match. Missing required context denies. Result explains allowed/denied and performs no financial transaction.', 'P0')
case(10, 'Authorization cannot combine unrelated grants', 'One role has permission in B1; a different role has a large authority or wider scope.',
     'Check permission with amount/rate but no authority code. || Supply authority from the unrelated role; combine an out-of-scope permission with an in-scope authority. || Repeat with all requirements on one valid assignment.',
     'Amount/rate needs an authority code. Permission, primary scope, dimensions and limit must all match the same assignment and an eligible authority row. Unrelated grants cannot be combined to elevate access. The complete matching assignment succeeds.', 'P0')

group('M1-MENU', 'Workspace navigation management', 'My security > Workspace navigation > Manage menus', 'S1 S5')
case(1, 'Add and edit workspace menu metadata', 'IAM_ROLE_MANAGE; spare supported route and test permission.',
     'Open Manage menus; add label, supported route, sort order and required permission. || Edit the label and permission; toggle ACTIVE/HIDDEN. || Inspect all-menu API and visible menus as authorized and unauthorized users.',
     'Metadata saves and API visibility follows status/permission. Backend authorization still protects hidden or directly requested routes. Compare rendered order/label with saved metadata; frontend uses fixed title/order overrides, so record unmet configurable-label/order expectations as defects rather than assuming they work.')
case(2, 'Menu validation and hierarchy API', 'Menu management permission; two menus and separate client fixture.',
     'Try invalid route, blank/oversized label, negative and over-99999 sort order and unknown permission. || Via API attempt cross-client parent, self-parent and an ancestor cycle. || Repeat save without management permission.',
     'Invalid metadata and cycles are rejected without breaking navigation. Parent must belong to same client. Unauthorized mutation fails. Supported route selection never turns a menu label into executable markup.', 'P1', 'UI and API')

group('M1-AUD', 'IAM audit trail', 'Administration > Audit trail', 'S1 S5 S9')
case(1, 'Security event search and traceability', 'IAM_AUDIT_READ; known login, password, status, access and authorization events.',
     'Open Security events; search partial event type and exact resource ID, then clear and try no match. || Compare actor, resource, result, correlation reference and timestamp against the originating actions. || Try over-120-character search via API.',
     'Latest 200 security events are ordered correctly and searchable. Successful and denied records are distinguished. Correlation links to the originating operation. Long search is rejected. Passwords, MFA secrets and bearer/refresh tokens are absent.')
case(2, 'Stored domain events and delivery labels', 'IAM_AUDIT_READ; stored and published outbox fixtures.',
     'Inspect Stored domain events for event, resource and recorded time. || Compare PUBLISHED and STORED labels with publishedAt. || Generate a role or access mutation and reload.',
     'Latest 100 rows match persisted outbox state; a committed mutation has corresponding audit/outbox evidence. STORED is not represented as broker delivery. Outbox display does not expose sensitive payload fields.')
case(3, 'Financial audit filters and failure isolation', 'Financial audit authority; known transactions on both ends of a date interval; reversible service failure.',
     'Search by transaction ID, account ID, each date and combined filters. || Try zero/negative IDs and reversed dates; inspect sent query. || Fail financial-audit service while IAM audit remains available; restore and use Search / refresh.',
     'Valid filters select correct records with inclusive business dates. Invalid input gives a clear error. Financial panel failure does not blank security/outbox panels. Retry restores records. Scope and authorization remain enforced; this panel is an M09 integration surface.')
case(4, 'Audit privilege and immutable evidence', 'Read-only nonauditor; authorized audit inspector; isolated database for mutation probes.',
     'Attempt audit/outbox endpoints without permission. || As auditor inspect evidence after a granted and denied operation. || In a disposable database use a restricted inspection account to test prohibited audit update/delete according to installed controls.',
     'Unauthorized reads fail. Existing evidence cannot be edited through UI/API. Installed immutability controls reject prohibited mutations; record deployment-specific failures separately. A denied business action is not represented as a successful mutation.', 'P0', 'UI API and database')

group('M1-KEY', 'Account holder access key', 'Account-holder access key page', 'S1 S8 S10')
case(1, 'Generate and rotate own holder key', 'Signed-in CUSTOMER C1 and a scoped officer using C1 old key.',
     'Generate a new account-holder key and securely capture once. || Reload page and inspect account-key listing/read responses. || Verify protected access with old versus new key; try rotation as employee.',
     'New key is shown once and cannot be retrieved later. Previous key stops working immediately. Rotation is CUSTOMER-only and applies to the current customer. Own authorized customer reads do not require a key.', 'P0')
case(2, 'Use clear and discard key in staff tab', 'Scoped employee; valid key, wrong key and second browser tab.',
     'Save a key using Use key for this tab; load protected account details. || Clear key and reload details, then save again and sign out. || Open another independent tab and reload the original page.',
     'Saving only stores the key in memory and does not itself validate access. Clear/sign-out remove it; page reload also clears the in-memory key. It is not persisted in sessionStorage/localStorage and does not appear in the second tab. Protected access is reevaluated.', 'P0')
case(3, 'Key cannot replace authorization or ownership', 'Scoped staff outside account scope, normal staff in scope, and active global BANK_ADMIN.',
     'Try valid key with missing permission/outside scope, wrong holder key in scope, and valid holder key in scope. || Test documented global BANK_ADMIN transaction-review exemption. || Revoke admin assignment while its session remains active and repeat.',
     'Key alone grants no permission or account scope. Only the authorized combination succeeds. Global BANK_ADMIN exemption applies to supported transaction review and remains audited; it must not be assumed for every protected service. Revoked privilege stops working on subsequent requests.', 'P0')

group('M2-DIR', 'Customer directory and record navigation', 'Customers & KYC > Customer directory', 'S2 S7')
case(1, 'Search customer name and CIF', 'CIF_READ scoped to B1; names with case, spaces, Unicode and history; a B2 customer.',
     'Search full/partial current name, historical name and CIF number; use case variants, surrounding spaces, blank reset and no match. || Open a result and compare CIF, branch, segment, status and effective KYC. || Search B2 customer explicitly.',
     'Search matches permitted name history/CIF case-insensitively; results remain scope filtered. Directory shows effective KYC expiry, not just stored VERIFIED. No match is explicit. Out-of-scope customer data does not leak.', 'P0')
case(2, 'All four customer tabs and return behavior', 'Customer A with data in all panels and customer B with mostly empty panels.',
     'Open A; select Profile, KYC and evidence, Relationships and consent, then Audit. || Open evidence, switch tabs and return. || Back to directory; open B and inspect selection; then sign out and sign in as another user.',
     'Only the selected tab panel is visible and aria-selected follows selection. All expected panels/actions remain reachable. New customer starts on Profile and clears prior case selection. User switch resets customer/case/tab selections; no data from A leaks into B.', 'P0')
case(3, 'Directory permissions empty states and cap', 'Read-only CIF user, maker, reviewer, no-CIF user; more than 200 in-scope customers.',
     'Compare Create customer and Expire due reviews visibility by permission. || Load an empty directory and a populated directory exceeding 200 matches. || Test status API filter and missing/out-of-scope IDs directly.',
     'Create requires CIF_CREATE; expiry requires KYC_REVIEW. APIs enforce permissions regardless of buttons. List is capped at 200; narrower search can locate a permitted customer. Current UI has name/CIF search, not a status filter or pagination; document any usability gap without inventing those controls.')
case(4, 'Create individual customer and initial case', 'Maker E1 with CIF_CREATE, CIF_READ, CIF_UPDATE and KYC_UPLOAD in B1; fresh unique test data.',
     'Create INDIVIDUAL using legal name, date of birth, B1 and optional segment. || Save and inspect Profile, Names & history and KYC cases. || Reload and correlate party, CIF, legal name, onboarding case and audit.',
     'One party, unique CIF number, pending legal name and DRAFT ONBOARDING case are created atomically. Initial normal flow is PROSPECT with unverified KYC. Maker is E1. Record opens successfully and retains dates/branch/segment; no login identity is implied.', 'P0')
case(5, 'Create organization and switch party type', 'Maker with create/read access; incorporation date not in future.',
     'Select ORGANIZATION; confirm Birth date hides/disables and Incorporation date becomes required. || Switch back and forth after entering both dates, then create organization. || Try both dates, neither date, future date and mismatched date type through API.',
     'Only the applicable date is submitted; organization stores incorporation date and no DOB. API rejects INVALID_PARTY_DATES and future INVALID_DATES. Type switching cannot send stale hidden dates. Failed creation leaves no partial records.')
case(6, 'Customer creation boundaries and branch scope', 'Field boundary matrix; B1 maker and B2 branch outside scope.',
     'Try blank/oversized legal name, branch and segment; Unicode name; today and future dates. || Enter branch with leading/trailing spaces in UI. || Create in B2 and repeat without CIF_CREATE; cancel a completed dialog.',
     'Contract limits and required fields are enforced. UI trims branch. Authorized valid Unicode data survives roundtrip. Out-of-scope creation fails; cancel has no side effects. Do not assume legal-name uniqueness: distinct customers may share a name.', 'P0')

group('M2-PRO', 'Profile tab and proposed customer information', 'Customer record > Profile', 'S2 S7 S11')
case(1, 'Profile details names and masked history', 'Customer with current/historical names, contacts, identifiers and addresses.',
     'Inspect Customer profile and expand Additional record fields. || Compare Names & history type, verification, valid-from and valid-to. || Inspect contacts/identifiers in DOM and API payload, then addresses and evidence reference.',
     'History and current records are distinguishable; dates/status/branch/segment are accurate. Contact and identifier values remain masked. Ciphertext, lookup HMAC and storage paths are absent from safe projections. Masking is verified on network responses, not only displayed cells.', 'P0')
case(2, 'Propose legal name and segment revision', 'E1 owns editable case on a previously verified customer; record current segment and name.',
     'Use Propose profile change with new legal name, new segment and reason. || Inspect pending history, CIF rowVersion and case proposed segment. || Propose again; then follow independent approval and compare current/history records.',
     'Pending revision is associated with the maker case. Effective CIF segment stays unchanged until approval; latest proposal is shown when reopening dialog. Approval promotes latest pending facts and retains dated history. Multiple proposals do not silently erase the previously verified history.', 'P0')
case(3, 'Profile revision rejection blank segment and concurrency', 'Editable periodic/remediation case; two E1 sessions with same CIF version.',
     'Propose a blank segment to clear it on approval; inspect pending indicator. || In separate fixtures reject or request more information after proposing a new segment. || Save competing profile revisions using one rowVersion.',
     'A deliberately blank proposed segment is distinguishable from no proposed change. Approval applies it; rejection leaves original segment and rejects pending facts; request-info allows rework. A stale profile version cannot overwrite a newer proposal.', 'P0')
case(4, 'Profile editing state and maker restrictions', 'Fixtures with no open case, another maker case, SUBMITTED, UNDER_REVIEW and CLOSED.',
     'Inspect Propose profile change, Add contact, Add identifier and Add address for each state. || Attempt every mutation through API despite hidden controls. || Return a submitted case to MORE_INFO_REQUIRED and retry as its maker.',
     'Edits require CIF_UPDATE, nonclosed CIF and own DRAFT/DOCUMENTS_PENDING/MORE_INFO_REQUIRED case. Other makers get CASE_MAKER_ONLY; no editable case gets EDIT_CASE_REQUIRED. Closed history is immutable. Request-info reopens editing only for the original maker.', 'P0')
case(5, 'Add and replace email and mobile contacts', 'Maker owns editable case; synthetic email and mobile.',
     'Add EMAIL qa.customer@example.test and MOBILE +919876543210. || Inspect masked hints and pending verification; submit and approve through the checker. || In a later case add a replacement of the same contact type and approve.',
     'Values are encrypted at rest and masked on read; pending contacts become verified only through KYC approval. Replacement history remains with validity intervals and current record is correct. No workflow claims a live SMS/email verification service.', 'P0')
case(6, 'Contact syntax and value boundaries', 'Editable own case; field matrix.',
     'Try email missing @, domain dot, embedded spaces and a valid plus-address. || Try mobile letters, forbidden symbols and values at implementation regex length boundaries. || Submit blank/type tampering and over-240-character value; inspect rejected persistence.',
     'Supported types are MOBILE/EMAIL. Invalid syntax yields INVALID_CONTACT; length/required rules also apply. The implemented mobile regex permits optional plus and 7 to 20 digits/spaces/hyphens; record any business-quality gap rather than asserting a country-specific phone validator.')
case(7, 'Add identifier with expiry and masking', 'Editable case; unique synthetic TAX_ID value; optional issuer.',
     'Add identifier with future expiry, then another supported free-text type without expiry. || Inspect uppercase type, masked hint, issuer and expiry. || Test expiry yesterday, today and tomorrow; complete approval for the valid case.',
     'Type is normalized to uppercase; stored value is encrypted with keyed lookup for duplicate detection. Expiry must be after today when supplied; today/past returns IDENTIFIER_EXPIRED. Valid identifier becomes verified through approval, not a live government lookup.', 'P0')
case(8, 'Duplicate identifier detection without customer leakage', 'Two editable customers with separate makers; known existing synthetic identifier.',
     'Add the same type/value to the same customer and another customer; include case/spacing variants matching normalization. || Submit concurrent identical identifiers from two sessions. || Inspect response, audit and table rows.',
     'Duplicate normalized type/value is rejected with DUPLICATE_IDENTIFIER without revealing the other customer. Concurrency must not persist duplicates. Verify database uniqueness/locking under target deployment; any race is a defect, not a successful duplicate-review flow.', 'P0')
case(9, 'Add each address type and optional fields', 'Editable case; ADDRESS evidence uploaded to this case.',
     'Create RESIDENTIAL, MAILING and REGISTERED address variants using line1, city, country IN and optional line2, region/postal code. || Link valid address document ID; repeat without optional evidence. || Review display and API, then approve KYC.',
     'Each supported type saves as proposed data with correct fields/evidence reference. Optional fields remain optional. Approval retains address history and marks accepted facts verified. Address table shows the documented summary while API retains full allowed details.')
case(10, 'Address evidence ownership and input validation', 'Two customers, an old approved case, current editable case and IDENTITY/ADDRESS documents.',
     'Try document from another CIF, old/noneditable case, different maker and wrong document type. || Try unknown ID, invalid/lowercase country, blank required fields and all length boundaries. || Retry with current same-maker ADDRESS evidence.',
     'Only ADDRESS evidence from this customer editable maker case may be linked. Invalid evidence returns INVALID_EVIDENCE without saving address. Country must be two uppercase letters; DTO limits apply. Valid evidence succeeds.', 'P0')

group('M2-KYC', 'KYC cases and independent decisions', 'Customer record > KYC and evidence', 'S2 S7 S11')
case(1, 'Open and select cases with correct defaults', 'Customers with no cases, approved VERIFIED/EXPIRED KYC and rejected KYC; no nonterminal case.',
     'Use Open new case and inspect default type. || Create ONBOARDING, PERIODIC and REMEDIATION in separate eligible fixtures. || Select Open evidence on current and historic cases; switch customers.',
     'Default is ONBOARDING with no case, PERIODIC after verified/expired KYC, otherwise REMEDIATION. Selected case evidence matches case ID and customer. Opening replacement case makes verified KYC PENDING and ACTIVE customer RESTRICTED immediately.', 'P0')
case(2, 'Only one open case per CIF', 'CIF with DRAFT, DOCUMENTS_PENDING, MORE_INFO_REQUIRED, SUBMITTED or UNDER_REVIEW case; CLOSED fixture.',
     'For each nonterminal state inspect Open new case and attempt API creation. || Race two new-case requests on a CIF with no open case. || Try creation for CLOSED CIF and after a terminal case.',
     'Existing open case blocks creation with CASE_EXISTS and hides action. Customer-row locking permits at most one concurrent open case. Closed CIF returns CUSTOMER_CLOSED. A nonclosed CIF can start a new case after APPROVED/REJECTED/EXPIRED.', 'P0')
case(3, 'Submission completeness identifies missing items', 'Own DRAFT case; isolate one missing item in each variant.',
     'Submit with missing current name, contact, address or identifier. || Repeat with missing IDENTITY or ADDRESS document and with only OTHER document. || Add all required records and documents, leaving evidence review PENDING, then submit.',
     'Incomplete variants return KYC_INCOMPLETE naming missing elements and remain editable. Submission needs current nonrejected profile facts plus both document types. Pending scans may be submitted for independent review; scanning is an approval gate, not a prerequisite to submission.', 'P0')
case(4, 'Submit freezes evidence and proposed data', 'Complete own DRAFT case and separate authorized nonmaker.',
     'Open Submit case; cancel then submit with reason and current version. || Inspect case/customer statuses and every Profile/Evidence action. || Attempt upload/profile mutation, duplicate submit and nonmaker submit through API.',
     'Case becomes SUBMITTED with timestamp/version; PROSPECT becomes PENDING_VERIFICATION and KYC becomes PENDING. Editing/upload freeze. Repeated/stale/nonmaker submission fails with state/version/maker guard and does not duplicate events.', 'P0')
case(5, 'Assign independent eligible officer', 'SUBMITTED case; E2 ACTIVE EMPLOYEE with effective KYC_REVIEW in B1.',
     'Assign E2 using its user ID. || Inspect UNDER_REVIEW, assigned officer and version. || As E2 review evidence; as another reviewer inspect actions and call decision API.',
     'Assignment routes review without approving. Only the assigned independent officer can attest/decide once assigned. Current branch/global permission is checked again at decision time. Queue/case shows correct officer and audit.', 'P0')
case(6, 'Officer assignment invalid and stale variants', 'Submitted case and users who are maker, inactive, customer, wrong branch, region-only, expired or dimension-restricted reviewers.',
     'Attempt assignment to every listed ineligible officer and an unknown ID. || Try assigning an unsubmitted case or using stale case rowVersion. || Assign a valid officer, revoke its review grant, then attempt a decision.',
     'Maker separation, employee/active status and effective review scope are enforced. Current assignment eligibility requires global or matching branch scope without product/currency dimensions. Stale/state-invalid requests fail; permission loss prevents later decisions.', 'P0')
case(7, 'Approve complete onboarding case', 'Submitted/under-review case with complete facts; latest document of every type CLEAN and VERIFIED; E2 independent reviewer.',
     'Open Decide KYC; select APPROVE, LOW risk, future periodic review due, reason code and comments. || Save and inspect case, customer, profile history, audit and eligibility facts. || Reload from another session.',
     'Case is APPROVED and retains independent review. Normal PROSPECT/PENDING_VERIFICATION onboarding customer becomes ACTIVE, KYC VERIFIED, risk/due are stored and proposed facts promoted. Approved segment becomes effective. Durable reads agree across tabs and sessions.', 'P0')
case(8, 'Approval risk and future review date validation', 'Reviewable complete case with clean verified latest documents.',
     'Try APPROVE without risk, without due date, due at current instant and in past. || Try each LOW/MEDIUM/HIGH risk with a future date in separate fixtures. || Use blank/oversized reason code/comments and invalid outcome via API.',
     'Approval requires valid risk and strictly future due date; missing/nonfuture values yield REVIEW_DUE_REQUIRED. UI rejects absent approval date before sending. Reason/comments and enum constraints are enforced. Rejected submission does not create a review or change case/customer.')
case(9, 'Latest evidence and identifier expiry gate approval', 'Reviewable case with identity v1 CLEAN/VERIFIED; v2 PENDING; OTHER evidence and identifier near expiry.',
     'Attempt approval with unreviewed v2 despite approved v1. || Review v2 but leave latest OTHER evidence unreviewed/rejected. || Test expired current identifier and then a newer valid replacement. || Complete all latest reviews and retry.',
     'Approval checks the latest version of every uploaded document type, including OTHER, for CLEAN/VERIFIED. Old verified versions cannot satisfy the gate. Current expired identifiers without newer valid replacement block approval. Only complete safe evidence succeeds.', 'P0')
case(10, 'Request more information and resubmit', 'Submitted case with E2 independent reviewer and original maker E1.',
     'Choose REQUEST_INFO with reason/comments. || As E1 open review history, correct profile and upload new evidence version. || Resubmit using refreshed rowVersion; as reviewer review latest documents and approve.',
     'Case becomes MORE_INFO_REQUIRED, decision time remains unset for that interim outcome and feedback is preserved. Original maker can edit; other staff cannot. Resubmission freezes changes again; all review rounds and document versions remain traceable.', 'P0')
case(11, 'Reject KYC and begin remediation', 'Reviewable case containing proposed name/segment/contact changes.',
     'Choose REJECT with reason/comments, omitting approval-only due/risk. || Inspect case/customer, pending facts, existing verified history and effective segment. || Attempt reopening the terminal case by upload/submit; then open a new remediation case.',
     'Case becomes REJECTED; customer KYC REJECTED and an ACTIVE customer is restricted. Pending revisions are rejected; previously verified history and original segment are preserved. Terminal case cannot be edited; a nonclosed customer may start a fresh case.', 'P0')
case(12, 'Maker separation and conflicting review decisions', 'Complete reviewable case; maker, assigned reviewer and another reviewer; synchronized API clients.',
     'Try all three outcomes as maker, even with KYC_REVIEW. || Send simultaneous approve/reject using one case version as authorized reviewer. || Retry old request and inspect reviews, case state and events.',
     'Maker can never decide its own case. Exactly one current-version decision commits; stale/state-conflicting decisions fail. Review history and resulting facts agree with the committed outcome; there is no second promotion or conflicting terminal state.', 'P0')

group('M2-DOC', 'KYC evidence upload review and download', 'KYC and evidence > Open evidence', 'S2 S7 S11')
case(1, 'Upload supported formats and document versions', 'Own editable case with KYC_UPLOAD; synthetic PDF, PNG and JPEG fixtures.',
     'Upload IDENTITY PDF, ADDRESS PNG and OTHER JPEG. || Upload replacement IDENTITY and inspect type/version, bytes, MIME, scan and verification. || Download later after authorized review and compare checksum with original.',
     'Upload recognizes actual file signature; starts with PENDING scan/verification and assigns increasing version per case/type. All versions remain visible. Encrypted storage is used; safe metadata contains no file path. Authorized download bytes match fixture.', 'P0')
case(2, 'File size and content type boundaries', 'Synthetic valid signature fixtures at 0, 1, 5242879, 5242880 and 5242881 bytes; invalid-signature text renamed .pdf.',
     'Upload every size variant, ensuring nonempty large fixtures contain valid signatures. || Try renamed text, spoofed submitted MIME, unsupported format and truncated signatures. || Try missing file and tampered documentType.',
     'Empty and over-5-MiB uploads fail; exactly 5242880 bytes is allowed if format-valid and transport limit permits. Signature, not filename or submitted Content-Type, determines PDF/PNG/JPEG. Invalid signatures/types fail with safe errors and no metadata/file remnants.', 'P0')
case(3, 'Upload ownership frozen states and concurrent versions', 'Own editable case, another maker case, submitted/terminal cases; simultaneous uploads.',
     'Upload as nonmaker, without KYC_UPLOAD and after submission/closure. || Concurrently upload same document type to an editable own case. || Inject isolated storage/database failure during upload and inspect persisted metadata/storage.',
     'Unauthorized/frozen uploads fail. Allowed concurrent uploads receive distinct sequential versions under case lock. Rollback cleans newly written files; failed storage writes do not leave successful metadata. Existing evidence remains readable.', 'P0', 'UI API and isolated fault injection')
case(4, 'Independent evidence attestation combinations', 'Submitted case; assigned independent reviewer; separate document per combination.',
     'Review CLEAN plus VERIFIED, CLEAN plus REJECTED, QUARANTINED plus REJECTED and FAILED plus REJECTED with reasons. || Try QUARANTINED/FAILED plus VERIFIED, missing reason and invalid enums. || Refresh evidence table.',
     'Allowed combinations persist with KycDocumentReviewed evidence. Nonclean documents cannot be VERIFIED and return UNSAFE_DOCUMENT. Invalid attempts do not alter prior status. These are explicit checker attestations, not proof that an automated antivirus engine ran.', 'P0')
case(5, 'Evidence review separation and assignment', 'Document uploaded by maker; another eligible reviewer; assigned-officer fixture.',
     'Review as maker/uploader, without KYC_REVIEW, wrong assigned officer and before submission. || Try after case APPROVED/REJECTED and with a document from another branch. || Review valid document as assigned independent officer.',
     'Every unauthorized/state-invalid attempt fails. Both maker and uploader must differ from reviewer. Assigned-officer and scope restrictions apply even when an API request bypasses UI. Valid review changes only the selected evidence.', 'P0')
case(6, 'Pending clean and unsafe evidence downloads', 'PENDING, CLEAN, QUARANTINED and FAILED documents; maker, read-only user and assigned reviewer.',
     'Download each status through visible controls and direct endpoint. || For PENDING try before submission, as maker and as wrong officer; then as assigned independent reviewer while reviewable. || Repeat CLEAN download with CIF_READ and proper scope.',
     'CLEAN downloads require authorized CIF read. PENDING is limited to the independent permitted reviewer while submitted/under review. QUARANTINED/FAILED content is blocked. UI button visibility matches backend policy and never substitutes for it.', 'P0')
case(7, 'Download integrity unavailable storage and audit', 'Clean evidence; disposable copy of evidence storage; authorized reviewer.',
     'Download and verify Content-Disposition filename, actual MIME, Cache-Control no-store and bytes. || In isolated fixture remove file, corrupt encrypted bytes or alter stored digest/reference; retry. || Expire session before download and inspect audit for successful downloads.',
     'Valid download is a real attachment and emits KycDocumentDownloaded. Missing file or integrity/path failure returns safe error without corrupted content or internal paths. Invalid session signs out through shared handling. Restore the isolated fixture after fault probes.', 'P0', 'UI API and isolated fault injection')

group('M2-LIFE', 'Customer status periodic review and closure', 'Profile > Change status and directory > Expire due reviews', 'S2 S7 S12')
case(1, 'Status changes and effective KYC activation gate', 'Verified customer, unverified customer and stored VERIFIED customer whose due date/identifier has expired.',
     'Inspect Change status choices in each fixture. || Move verified customer to RESTRICTED, DORMANT and ACTIVE with reasons. || Try ACTIVE for unverified/effectively expired customer through API and test stale rowVersion.',
     'ACTIVE is offered/accepted only with effective VERIFIED KYC. Stored VERIFIED alone is insufficient after expiry. Status changes use current rowVersion and preserve history; KYC_REQUIRED blocks invalid activation. Closed profile offers no changes.', 'P0')
case(2, 'Periodic expiry before and after sweep', 'Verified customers due just before/at/after controlled now; current/expired identifiers; scoped reviewer B1.',
     'Before sweep inspect directory, detail and eligibility-facts around due boundaries. || Run Expire due reviews; compare returned count and affected customers/cases. || Repeat sweep, then inspect B2 and CLOSED fixtures.',
     'Effective KYC is EXPIRED immediately when due/identifier expires, even before sweep. Sweep materializes KYC EXPIRED, restricts ACTIVE customers and emits KycExpired within scope. Due approved cases expire as applicable. Repeat returns no extra changes; B2/closed records remain untouched.', 'P0')
case(3, 'Reverify restricted or dormant customer', 'RESTRICTED or DORMANT customer with expired/rejected KYC; new editable case.',
     'Complete a new case, independently attest documents and approve with future due date. || Inspect banner/status and attempt eligible account/product action. || Use separate permitted Change status to ACTIVE and retry.',
     'Approval makes KYC VERIFIED but preserves RESTRICTED or DORMANT state. Restricted verified profile shows the staff-review notice. Explicit status review is needed before activation; approval does not silently remove an existing customer restriction.', 'P0')
case(4, 'Closure blocked by every financial dependency', 'Disposable separate customers with open primary/joint accounts, active term deposit, pending card application, live card, open primary/party loan application and live loan facility.',
     'Attempt CLOSED with reason on each fixture. || Inspect response and all related identity, CIF, case, relationship and consent data. || Resolve the dependency through its normal workflow and repeat where safe.',
     'Closure fails with OPEN_ACCOUNTS, OPEN_TERM_DEPOSITS, OPEN_CREDIT_CARDS or OPEN_LOANS as appropriate. Primary and related-party exposure are covered. A failed closure does not revoke links/sessions or alter consents/cases. Retest after all obligations reach allowed terminal states.', 'P0')
case(5, 'Successful closure cascades and terminal history', 'No financial obligations; open KYC case, pending facts, granted consent, incoming/outgoing relationships and a linked customer login.',
     'Choose CLOSED with current version and reason; save. || Inspect every customer tab, linked IAM identity, sessions and refresh tokens. || Attempt profile/case/consent/relationship creation and reactivation afterward.',
     'CIF becomes CLOSED; open cases EXPIRED; pending facts rejected; active consents withdrawn; active incoming/outgoing relationships ended. IAM links and sessions/refresh tokens are revoked. Closure is terminal and historical rows remain readable only to authorized staff.', 'P0')
case(6, 'Closure handles one or multiple linked CIFs', 'Two login fixtures: one linked only to closing CIF A; another linked to A plus another ACTIVE CIF B.',
     'Close A after clearing obligations for each fixture. || Inspect login status and existing sessions. || Attempt fresh sign in and read A/B as appropriate.',
     'All affected customer sessions end. Login with no remaining active CIF becomes DISABLED; login with another active CIF may remain ACTIVE and can sign in again for B. Closed A is absent from effective SELF links. No credential or unrelated CIF is deleted.', 'P0')

group('M2-REL', 'Relationships and consent tab', 'Customer record > Relationships and consent', 'S2 S7')
case(1, 'Capture consent with evidence', 'Nonclosed CIF and CIF_UPDATE in scope; new purpose QA_CONTACT.',
     'Select Capture consent; enter purpose, channel and evidence reference. || Save, reload and compare all three values and GRANTED status. || Try blank/oversized fields and duplicate active purpose.',
     'Consent evidence persists with capture time and audit. Only one active consent for that CIF/purpose is accepted; duplicate returns CONSENT_ALREADY_ACTIVE. Invalid inputs and closed/out-of-scope CIF create no consent. This capture does not claim a universal cross-domain consent enforcement engine.', 'P0')
case(2, 'Withdraw and recapture consent', 'GRANTED consent and authorized maker.',
     'Open Withdraw and cancel; reopen and confirm. || Inspect status/history and repeat withdrawal via API; use another CIF consent ID. || Capture a fresh grant for the same purpose with new evidence.',
     'Cancel leaves GRANTED. Confirm marks WITHDRAWN with time and keeps original evidence; action disappears. Repeated or mismatched withdrawal returns CONSENT_NOT_ACTIVE. New grant is a separate record, not an overwrite.', 'P0')
case(3, 'Create party relationship for every authority', 'Distinct nonclosed CIF A and B; source CIF_UPDATE and target CIF_READ.',
     'Create distinct test relationship types with NONE, VIEW, TRANSACT and REPRESENT. || Test no expiry and future expiry. || Inspect source and target views, target party ID, authority and validity; reload.',
     'Directed relationship persists with correct source/target and dates; related records appear accurately. These attributes alone create neither a digital customer link nor an account mandate. Target customer visibility is required in addition to source edit permission.', 'P0')
case(4, 'Relationship negative validation and duplicates', 'A/B customers and one CLOSED/out-of-scope customer.',
     'Try self target, nonexistent/closed target, closed source, no target read permission and past/equal-now expiry. || Try unsupported authority, blank/oversized type/ID and duplicate active source-target-type. || Repeat duplicate concurrently.',
     'SELF_RELATIONSHIP, CUSTOMER_CLOSED, INVALID_DATES, RELATIONSHIP_EXISTS or authorization/validation failure protects each invalid case. No duplicate active relationship should survive concurrency; capture any database race as defect. Another customer record is not exposed in errors.', 'P0')
case(5, 'End outgoing relationship and enforce direction', 'Active future-ended/open-ended outgoing relation and incoming relation.',
     'Cancel then confirm End on the outgoing relation; end a just-created relation. || Inspect validTo and history; repeat API end. || Attempt ending the incoming relation from the target CIF; inspect controls after natural expiry.',
     'Only active outgoing relations offer End. End stores a positive validity interval, even immediately after creation; history remains. Repeated/incoming/expired end attempts fail with RELATIONSHIP_NOT_ACTIVE. Source and target selection cannot be swapped accidentally.')

group('M2-AUD', 'Customer audit tab and eligibility facts', 'Customer record > Audit', 'S2 S7')
case(1, 'Customer audit complete flow trace', 'A customer with creation, profile, evidence, submission, assignment, information request, approval, consent and relationship actions.',
     'Open Audit and compare event/result/actor/time with known operations. || Switch customers and back; perform another valid action and reload. || Inspect safe audit/outbox projections through authorized tooling.',
     'Customer timeline is scoped to its CIF and records committed domain events with correct actor/time. No customer names, raw contacts/identifiers or document content is placed in event payloads. UI order and reload are consistent; prior-customer events never persist.', 'P0')
case(2, 'Audit and facts cannot bypass customer scope', 'B1 staff, B2 customer, SELF-linked C1 and unlinked C2.',
     'Call each customer audit and eligibility-facts endpoint as permitted and forbidden identities. || Inspect allowed facts: status, effective KYC, branch, segment, risk, party type, dates and verified country. || Repeat after link/grant revocation and KYC expiry.',
     'CIF_READ and exact resource scope protect both endpoints. Facts contain only intended integration fields and current effective KYC; no plaintext identity evidence. Authorization updates apply immediately and CustomerFactsRead is audited.', 'P0', 'API and UI')

group('X-FLOW', 'End to end flows across modules', 'IAM and Customers & KYC plus integration checkpoints', 'S1 S2 S5 S6 S7 S8 S12')
case(1, 'Staff provisioning through first KYC approval', 'Admin A, checker B and fresh employee E1; no customer data yet.',
     'Create and activate E1; create maker role; request B1 grant as A and approve as B. || Sign in E1; create individual, add contact/identifier, upload identity/address evidence and propose address. || Submit; assign independent E2, attest and approve. || Verify ACTIVE/VERIFIED CIF and both module audits.',
     'Every handoff succeeds using only assigned permissions. Maker cannot self-approve at either level. Result is one governed staff assignment and one fully verified customer with complete history; demo assumed-KYC path is not used.', 'P0')
case(2, 'Verified CIF to digital customer self access', 'ACTIVE verified CIF from full KYC flow; standard customer identity, no existing link or grant.',
     'Create/activate digital customer, link verified CIF and independently approve SELF read role. || Sign in customer; inspect My security and own CIF/API access; attempt other customer CIF. || Check authorized downstream account eligibility without assuming link grants operation rights.',
     'Customer sees only linked authorized data. IAM identity, M02 customer and account mandates remain distinct. Unlinked data is denied. Failed KYC/inactive CIF or missing role prevents the corresponding protected action.', 'P0')
case(3, 'Privilege revocation during open KYC work', 'Maker/reviewer with case dialog open and valid sessions; independent administrator/checker.',
     'Request and approve revocation of the maker edit grant while its dialog remains open; submit the old form. || Repeat for reviewer KYC_REVIEW while decision dialog is open. || Restore via a new governed grant and retry after reload.',
     'Next API request reevaluates current authority and denies stale privileges. No partial profile, document review or KYC decision commits. Freshly restored authority works according to scope; stale UI visibility never authorizes an action.', 'P0')
case(4, 'Periodic revision blocks premature downstream use', 'ACTIVE/VERIFIED CIF with existing segment and an eligible product fixture.',
     'Open periodic case, propose a segment that would qualify for another offer, and query eligibility facts/discovery. || Request more information then approve corrected evidence. || Inspect still-restricted status; activate separately and recheck.',
     'Opening case sets KYC PENDING and restricts active CIF. Unapproved segment cannot qualify downstream account/loan offers. Approval applies segment but preserves restriction; eligibility reflects final approved facts plus customer status. No account or loan is opened as a side effect.', 'P0')
case(5, 'Closure and in flight authorization stay atomic', 'CIF ready for closure, linked customer in a live session, no financial obligations; isolated concurrent clients.',
     'Start a protected read and close CIF from staff session. || After closure commits attempt CIF/account access, refresh and sign in. || Inject closure transaction failure before commit in a separate fixture and inspect all linked state.',
     'All requests after committed closure honor revoked links/sessions and remaining-CIF login rule. A request already completed before closure is distinguished by timestamps. A failed transaction does not leave half-closed CIF or partially revoked IAM state.', 'P0', 'UI API and isolated fault injection')
case(6, 'Identity and banking service outage recovery', 'Microservice deployment; reversible fault isolation and nonproduction data.',
     'Make identity authorization unavailable and attempt M02 protected reads/writes. || Restore identity, then fail banking/CIF service while keeping IAM available. || Retry from each page after recovery and inspect database/audit for partial operations.',
     'Authorization dependency failure never grants access by default. Users see recoverable errors without false success. Service recovery restores correct current state, and failed operations do not leave partial identity/CIF/evidence records. Record actual error contract and correlation reference.', 'P0', 'UI API and isolated fault injection')

group('X-UI', 'Shared interaction accessibility and resilience', 'Apply to every in-scope page tab and mutation dialog', 'S1 S2 S3 S13')
case(1, 'Dialog cancel validation and duplicate clicks', 'Run against every mutation dialog listed in the coverage inventory, using fresh disposable data.',
     'Open dialog; cancel through Cancel, X and Escape where supported. || Submit invalid data, correct it, then save during a delayed response and double-click. || Reopen and inspect persisted data; test server rejection and retry.',
     'Cancel paths perform no mutation. Error is visible with retained values and usable controls. Save stays disabled while pending; success creates exactly the intended record. APIs without replay guarantees are checked for duplicate impact, not assumed idempotent.', 'P1')
case(2, 'Keyboard focus and accessible tab semantics', 'Keyboard-only run plus accessibility inspector on supported browser.',
     'Use skip link, navigation search, every tab and all dialog controls without mouse. || Check tab names, selected state, focus visibility/order, dialog focus containment and return focus. || Activate tabs with Enter/Space and assess arrow-key behavior against selected accessibility standard.',
     'Every workflow is reachable with visible focus and meaningful accessible labels. Hidden panels are not focusable. Errors/status changes are announced. Tablist relationship and keyboard gaps are filed as accessibility defects; current markup is not assumed to meet the full standard.', 'P1')
case(3, 'Responsive layout zoom and long content', 'Supported desktop browsers, mobile-width viewport, long synthetic names/IDs/reasons and populated history.',
     'Run main flows at 1440, 1024 and 390 CSS pixels and at 200 percent zoom. || Open mobile Menu, search pages, clear/no-match query, scroll tables and long dialogs. || Inspect every M02 tab and M1 security/role/user panels.',
     'Labels, inputs, Save/Cancel and row actions remain reachable and legible. Tables can scroll without hiding controls. Long content wraps safely; no clipped validation or overlapping focus targets. Menu expanded/current-page state remains accurate.', 'P2')
case(4, 'Localization dates and numeric presentation', 'Each locale listed by the runtime language picker; date/amount/rate fixtures.',
     'Change language before sign in, inside each M1 page and each M2 tab, including open dialogs. || Inspect status/error translation, Unicode names and date-time roundtrip. || Compare displayed local dates with submitted UTC instants and documented business timezone.',
     'Language switches preserve identity, tab and unsaved values where supported without altering business data. Labels/errors remain understandable and encoded safely. Due dates and validity boundaries represent the intended instant; amounts/rates retain precision. Record unsupported translations explicitly.', 'P2')
case(5, 'Safe text rendering and request errors', 'Synthetic markup strings in allowed names/reasons/labels; network/API fault controls.',
     'Enter harmless markup-like text in legal name, reason, role display and menu label; inspect lists, details and errors. || Simulate network failure, non-JSON response, 401/403 and validation response. || Retry and change route while earlier load is pending.',
     'Text is escaped and cannot execute script. Failures expose useful safe message/correlation ID without stack trace or sensitive values. Session-ending 401 clears authentication; other errors preserve appropriate state. Late responses cannot overwrite the selected page.', 'P0')
case(6, 'Direct API denial and no partial persistence', 'For every protected read/write: valid user, no permission, wrong branch/CIF, expired/revoked session and malformed ID.',
     'Replay one representative valid request per endpoint family from the source inventory using each unauthorized variant. || Compare before/after business rows and available denial audit. || Repeat a stale-version mutation and a terminal-state action.',
     'Protected endpoints enforce authentication, permission, resource scope, state and version independently of UI. Denial never creates business success or leaks secrets. Distinguish safe 403/404/validation outcomes by endpoint rather than requiring one universal code.', 'P0', 'API and database')
