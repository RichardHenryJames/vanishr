ALTER TABLE accounts DROP CONSTRAINT accounts_handle_check;
ALTER TABLE accounts ADD CONSTRAINT accounts_handle_check
    CHECK (handle ~ '^[a-z0-9_-]{3,32}$');
