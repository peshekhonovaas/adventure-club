CREATE TABLE messages (
                          id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                          session_id UUID        NOT NULL REFERENCES sessions(id),
                          role       VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
                          content    TEXT        NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Every history query filters and orders by these two columns
CREATE INDEX idx_messages_session_created ON messages (session_id, created_at);