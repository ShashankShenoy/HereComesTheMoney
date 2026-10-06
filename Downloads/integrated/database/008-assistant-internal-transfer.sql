-- Apply after 007. Permit a separate short-lived action for an internal ledger transfer.
-- Add the broader check before removing the old one, so the table remains constrained.
ALTER TABLE MBX_ASSISTANT_INTENT ADD CONSTRAINT MBX_ASSISTANT_INTENT_ACTION_V2_CK
  CHECK (ACTION_CODE IN ('PAYMENT_INITIATE','BENEFICIARY_VERIFY','INTERNAL_TRANSFER'));
ALTER TABLE MBX_ASSISTANT_INTENT DROP CONSTRAINT MBX_ASSISTANT_INTENT_ACTION_CK;
