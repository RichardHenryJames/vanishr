CREATE UNIQUE INDEX accounts_single_admin ON accounts (user_type) WHERE user_type = 'ADMIN';

CREATE TABLE admin_identity (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    user_id UUID NOT NULL UNIQUE REFERENCES accounts(id) ON UPDATE RESTRICT ON DELETE RESTRICT
);

INSERT INTO admin_identity (user_id) SELECT id FROM accounts WHERE user_type = 'ADMIN';

CREATE FUNCTION protect_admin_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_identity_is_permanent' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER admin_identity_immutable
BEFORE UPDATE OR DELETE OR TRUNCATE ON admin_identity
FOR EACH STATEMENT EXECUTE FUNCTION protect_admin_identity();

CREATE FUNCTION enforce_admin_identity() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM "${flyway:defaultSchema}".admin_identity WHERE user_id = NEW.id) THEN
        RAISE EXCEPTION 'admin_identity_mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_admin_identity
BEFORE INSERT OR UPDATE OF id, user_type ON accounts
FOR EACH ROW WHEN (NEW.user_type = 'ADMIN')
EXECUTE FUNCTION enforce_admin_identity();
