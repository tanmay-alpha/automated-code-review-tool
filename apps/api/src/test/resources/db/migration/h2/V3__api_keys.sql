-- API keys for VS Code extension (and any future programmatic client).
-- Each key is bound to a user; only the lookup prefix is stored in cleartext,
-- the full key is bcrypt-hashed.
--
-- H2 test-only variant: PostgreSQL's `TIMESTAMPTZ` type is not recognized
-- by H2 2.x in PG-compat mode — the canonical equivalent is
-- `TIMESTAMP WITH TIME ZONE`. The H2 dialect stores this as the
-- same value Postgres would, so application reads/writes via
-- Hibernate's `Instant` mapping remain identical.
CREATE TABLE api_keys (
    id            UUID                    PRIMARY KEY,
    user_id       UUID                    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label         VARCHAR(60)             NOT NULL,
    prefix        VARCHAR(16)             NOT NULL UNIQUE,
    key_hash      VARCHAR(80)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMP WITH TIME ZONE,
    revoked       BOOLEAN                 NOT NULL DEFAULT false
);

CREATE INDEX idx_api_keys_user_id ON api_keys(user_id);
CREATE INDEX idx_api_keys_prefix  ON api_keys(prefix);
