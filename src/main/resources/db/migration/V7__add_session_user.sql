-- Tie an adventure session to the "hero" who is playing it, so the last adventure
-- can be reloaded after the hero logs in again (even on a different browser/device).
-- Nullable: sessions created before auth existed have no owner.
ALTER TABLE sessions ADD COLUMN username VARCHAR(100);

-- Fast lookup of a hero's most recent adventure.
CREATE INDEX ix_sessions_username_created_at ON sessions (username, created_at DESC);
