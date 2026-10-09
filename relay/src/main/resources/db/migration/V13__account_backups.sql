-- Opaque, client-encrypted account backup. The relay never receives the recovery key and does not interpret the bytes.
-- Each account has at most one blob. Every write sets a bounded expiry (90 days) in the same statement.
-- The bound is written in hours so it is absolute and never shifts with a session time zone's daylight-saving change.
CREATE TABLE account_backups (
    user_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    ciphertext BYTEA NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (octet_length(ciphertext) BETWEEN 64 AND 524288),
    CHECK (expires_at > updated_at AND expires_at <= updated_at + INTERVAL '2160 hours')
);

-- Ciphertext is incompressible, so skip pointless TOAST compression.
ALTER TABLE account_backups ALTER COLUMN ciphertext SET STORAGE EXTERNAL;

CREATE INDEX account_backups_expiry ON account_backups(expires_at);
