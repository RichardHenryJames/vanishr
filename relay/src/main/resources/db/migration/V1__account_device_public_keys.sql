CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    handle VARCHAR(32) NOT NULL UNIQUE CHECK (handle ~ '^[a-z0-9_]{3,32}$'),
    password_hash VARCHAR(100) NOT NULL
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES accounts(id) ON DELETE CASCADE,
    identity_key VARCHAR(64) NOT NULL,
    auth_version UUID NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prekeys (
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    id INTEGER NOT NULL CHECK (id > 0),
    public_bundle JSONB NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (device_id, id)
);

CREATE INDEX prekeys_expiry ON prekeys(expires_at);