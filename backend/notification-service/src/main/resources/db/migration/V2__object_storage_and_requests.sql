-- Sender pictures are object-storage keys now (signed per request), not URLs.
ALTER TABLE notifications RENAME COLUMN image_url TO image_key;
UPDATE notifications SET image_key = NULL;

-- New kind: a message from someone whose chat you haven't accepted yet.
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('NEW_MESSAGE', 'MESSAGE_REQUEST', 'ADDED_TO_GROUP'));
