CREATE TABLE fallback_prekeys (
    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    id INTEGER NOT NULL CHECK (id > 0),
    public_bundle JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '30 days')
);

CREATE INDEX fallback_prekeys_expiry ON fallback_prekeys(expires_at);
