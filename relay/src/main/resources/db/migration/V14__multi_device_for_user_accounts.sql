-- A non-admin account may now hold more than one registered device (a device cap is enforced in application
-- code, not here). The admin account is kept to exactly one device by application code in registerDevice;
-- nothing here distinguishes account types, since the devices table has none of its own.
ALTER TABLE devices DROP CONSTRAINT devices_user_id_key;
CREATE INDEX devices_user_id ON devices(user_id);
