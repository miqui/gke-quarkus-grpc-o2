CREATE INDEX ix_messages_author_id_created_at_id ON messages (author_id, created_at DESC, id DESC);
