CREATE TABLE account_deletion_receipts (
    proof_digest VARCHAR(44) PRIMARY KEY CHECK (proof_digest ~ '^[A-Za-z0-9+/]{43}=$'),
    user_id UUID NOT NULL UNIQUE,
    state VARCHAR(7) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours'),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '24 hours')
);

-- No account foreign key: the bounded confirmation must survive account erasure.
CREATE INDEX account_deletion_receipts_expiry ON account_deletion_receipts(expires_at);
