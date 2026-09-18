ALTER TABLE accounts ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE accounts ADD COLUMN google_subject VARCHAR(255) UNIQUE;
ALTER TABLE accounts ADD CONSTRAINT account_authentication_method
    CHECK ((password_hash IS NOT NULL AND google_subject IS NULL)
        OR (password_hash IS NULL AND google_subject IS NOT NULL));