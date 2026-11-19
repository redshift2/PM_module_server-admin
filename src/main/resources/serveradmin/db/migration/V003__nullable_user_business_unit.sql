-- Allow users without a business unit; matches crew.User nullable constraint.
ALTER TABLE TAACK_USERS ALTER COLUMN BUSINESS_UNIT DROP NOT NULL;
