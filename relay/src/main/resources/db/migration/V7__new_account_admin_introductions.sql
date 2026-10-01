CREATE TABLE admin_introductions (
    user_id UUID PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    admin_id UUID NOT NULL REFERENCES admin_identity(user_id) ON DELETE RESTRICT,
    CHECK (user_id <> admin_id)
);

CREATE INDEX admin_introductions_admin ON admin_introductions (admin_id, user_id);

CREATE FUNCTION introduce_new_account_to_admin() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO "${flyway:defaultSchema}".admin_introductions (user_id, admin_id)
    SELECT NEW.id, pin.user_id
    FROM "${flyway:defaultSchema}".admin_identity pin
    WHERE NEW.user_type = 'USER' AND NEW.id <> pin.user_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_admin_introduction
AFTER INSERT ON accounts
FOR EACH ROW EXECUTE FUNCTION introduce_new_account_to_admin();
