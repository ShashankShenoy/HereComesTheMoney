-- Existing Oracle installations need these columns before deploying the
-- Module 2 profile workflow. Fresh installations include them in 001.
ALTER TABLE M02_KYC_CASE ADD (
  PROPOSED_SEGMENT_CODE VARCHAR2(40 CHAR),
  SEGMENT_CHANGE_PENDING CHAR(1) DEFAULT 'N' NOT NULL
);
