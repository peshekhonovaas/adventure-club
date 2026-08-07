-- Real backend auth: a "hero" account (username + BCrypt-hashed secret word).
-- Usernames are unique case-insensitively so "Grogu" and "grogu" can't both register.
CREATE TABLE users (
                       id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                       username   VARCHAR(100) NOT NULL,
                       password   VARCHAR(255) NOT NULL,
                       created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_users_username_lower ON users (LOWER(username));
