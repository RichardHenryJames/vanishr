CREATE TABLE private_groups (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    epoch UUID NOT NULL,
    closed_at TIMESTAMPTZ
);

CREATE TABLE group_members (
    group_id UUID NOT NULL REFERENCES private_groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    identity_key VARCHAR(64) NOT NULL,
    state VARCHAR(8) NOT NULL CHECK (state IN ('INVITED', 'ACTIVE')),
    invited_until BIGINT NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX group_members_user ON group_members(user_id, device_id);