-- "Delete chat" is per user: the chat leaves your list and its history up to cleared_seq is
-- hidden for you only. A new message from the other person brings the chat back.
ALTER TABLE conversation_members
    ADD COLUMN cleared_seq bigint  NOT NULL DEFAULT 0,
    ADD COLUMN deleted     boolean NOT NULL DEFAULT false;
