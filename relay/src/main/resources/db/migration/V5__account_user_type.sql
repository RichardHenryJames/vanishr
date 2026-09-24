ALTER TABLE accounts ADD COLUMN user_type VARCHAR(16) NOT NULL DEFAULT 'USER';
ALTER TABLE accounts ADD CONSTRAINT account_user_type CHECK (user_type IN ('USER', 'ADMIN'));