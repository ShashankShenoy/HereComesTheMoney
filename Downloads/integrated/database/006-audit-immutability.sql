-- Oracle only. The local H2 fixture intentionally does not emulate triggers.
CREATE OR REPLACE TRIGGER MBX_AUDIT_IMMUTABLE
BEFORE UPDATE OR DELETE ON MBX_AUDIT
BEGIN
 RAISE_APPLICATION_ERROR(-20951,'Money Bags business audit events are append-only');
END;
/
