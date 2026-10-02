ALTER TABLE accounts ADD COLUMN deletion_state VARCHAR(8) NOT NULL DEFAULT 'ACTIVE'
    CHECK (deletion_state IN ('ACTIVE', 'DELETING'));

CREATE TABLE account_blocks (
    blocker_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX account_blocks_target ON account_blocks(blocked_id, blocker_id);

-- Erasure removes the account, never the immutable reservation or its protections.
ALTER TABLE admin_identity DROP CONSTRAINT admin_identity_user_id_fkey;

CREATE FUNCTION require_active_admin_reservation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM id FROM "${flyway:defaultSchema}".accounts
        WHERE id = NEW.user_id AND deletion_state = 'ACTIVE' FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'admin_account_unavailable' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER admin_identity_existing_account
BEFORE INSERT ON admin_identity
FOR EACH ROW EXECUTE FUNCTION require_active_admin_reservation();

CREATE FUNCTION protect_reserved_account() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF EXISTS (SELECT 1 FROM "${flyway:defaultSchema}".admin_identity WHERE user_id = NEW.id) THEN
            RAISE EXCEPTION 'account_identity_reserved' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.id <> OLD.id AND EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".admin_identity WHERE user_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'account_identity_reserved' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${flyway:defaultSchema}".admin_identity WHERE user_id = OLD.id) THEN
        IF TG_OP = 'DELETE' THEN
            IF OLD.deletion_state <> 'DELETING' THEN
                RAISE EXCEPTION 'account_erasure_required' USING ERRCODE = '23514';
            END IF;
        ELSIF NEW.id <> OLD.id THEN
            RAISE EXCEPTION 'account_identity_reserved' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_admin_reservation
BEFORE INSERT OR UPDATE OF id OR DELETE ON accounts
FOR EACH ROW EXECUTE FUNCTION protect_reserved_account();

CREATE TRIGGER accounts_admin_truncate
BEFORE TRUNCATE ON accounts
FOR EACH STATEMENT EXECUTE FUNCTION protect_admin_identity();

CREATE OR REPLACE FUNCTION introduce_new_account_to_admin() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO "${flyway:defaultSchema}".admin_introductions (user_id, admin_id)
    SELECT NEW.id, pin.user_id
    FROM "${flyway:defaultSchema}".admin_identity pin
    JOIN "${flyway:defaultSchema}".accounts a ON a.id = pin.user_id
    WHERE NEW.user_type = 'USER' AND NEW.id <> pin.user_id AND a.deletion_state = 'ACTIVE';
    RETURN NEW;
END;
$$;
