CREATE TABLE sessions (
                          id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                          child_name VARCHAR(100) NOT NULL,
                          agent_name VARCHAR(100) NOT NULL,
                          interests  TEXT        NOT NULL,
                          created_at TIMESTAMPTZ NOT NULL    DEFAULT NOW()
);