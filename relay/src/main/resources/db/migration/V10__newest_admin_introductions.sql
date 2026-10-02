ALTER TABLE admin_introductions
    ADD COLUMN introduced_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX admin_introductions_admin_newest
    ON admin_introductions (admin_id, introduced_at DESC, user_id DESC);
