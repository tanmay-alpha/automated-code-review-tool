-- H2 mirror of V22__review_publications.sql.
CREATE TABLE review_publications (
    id                    UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
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
    available_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processing_started_at TIMESTAMP,
    comment_id            BIGINT,
    comment_url           TEXT,
    review_id             BIGINT,
    published_head_sha    VARCHAR(40),
    published_at          TIMESTAMP,
    last_error            TEXT,
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_review_publication_status
        CHECK (status IN ('pending', 'published', 'failed', 'superseded', 'skipped')),
    CONSTRAINT chk_review_publication_attempts
        CHECK (attempt_count >= 0 AND max_attempts > 0 AND attempt_count <= max_attempts)
);

CREATE INDEX idx_review_publications_due ON review_publications(status, available_at);
CREATE INDEX idx_review_publications_pr ON review_publications(pr_id);
