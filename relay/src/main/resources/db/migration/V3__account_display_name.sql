ALTER TABLE accounts ADD COLUMN display_name VARCHAR(40);
ALTER TABLE accounts ADD CONSTRAINT account_display_name_length
    CHECK (display_name IS NULL OR char_length(trim(display_name)) BETWEEN 1 AND 40);