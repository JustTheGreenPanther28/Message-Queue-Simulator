-- Profile pictures move from local disk to object storage: store the object key, not a URL.
-- Old disk files aren't migrated (local dev data), so existing pictures are cleared.
ALTER TABLE users RENAME COLUMN avatar_url TO avatar_key;
UPDATE users SET avatar_key = NULL;
