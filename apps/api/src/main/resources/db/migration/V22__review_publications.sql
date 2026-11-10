-- Durable, idempotent publication of review results to GitHub.
--
-- One row per reviewed delivery (= one PR head). The review transaction
-- creates it together with the findings, so a crash after that commit
-- cannot lose the comment; a separate leased worker publishes it with
-- backoff. The sticky summary comment is located by a hidden marker, so a
-- retry after a lost acknowledgement edits the comment instead of posting
-- a duplicate. comment_id / review_id record the external effect.
--
-- status: pending    -> waiting for (or holding) a publish lease
--         published  -> summary comment (and inline review, if enabled) written
--         failed     -> terminal GitHub error or attempts exhausted (last_error)
--         superseded -> the PR head moved on before publication
--         skipped    -> comments disabled for the repository
CREATE TABLE review_publications (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id           VARCHAR(100) NOT NULL UNIQUE
                                       REFERENCES processed_webhooks(delivery_id) ON DELETE CASCADE,
    pr_id                 UUID         NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    head_sha              VARCHAR(40)  NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'pending',
    inline_comments       BOOLEAN      NOT NULL DEFAULT FALSE,
    engine                VARCHAR(60),
    inline_finding_ids    TEXT,
    attempt_count         INT          NOT NULL DEFAULT 0,
    max_attempts          INT          NOT NULL DEFAULT 8,
    available_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processing_started_at TIMESTAMPTZ,
    comment_id            BIGINT,
    comment_url           TEXT,
    review_id             BIGINT,
    published_head_sha    VARCHAR(40),
    published_at          TIMESTAMPTZ,
    last_error            TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_review_publication_status
        CHECK (status IN ('pending', 'published', 'failed', 'superseded', 'skipped')),
    CONSTRAINT chk_review_publication_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts)
);

CREATE INDEX idx_review_publications_due ON review_publications(status, available_at);
CREATE INDEX idx_review_publications_pr ON review_publications(pr_id);
