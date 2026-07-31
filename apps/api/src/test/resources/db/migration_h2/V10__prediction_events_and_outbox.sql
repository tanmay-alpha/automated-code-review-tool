-- ============================================
-- V10 (H2 mirror): prediction events, sample reviews, outbox
-- ============================================

CREATE TABLE IF NOT EXISTS ml.prediction_events (
    id                    UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    code_sample_id        UUID         NULL,
    pull_request_id       UUID         NOT NULL,
    file_path             TEXT,
    reported_line_start   INT,
    reported_line_end     INT,
    anti_pattern_id       VARCHAR(80)  NOT NULL,
    category              VARCHAR(30)  NOT NULL,
    severity              VARCHAR(10)  NOT NULL,
    confidence            DECIMAL(5,4) NOT NULL,
    engine                VARCHAR(60)  NOT NULL,
    model_version         VARCHAR(100) NOT NULL,
    taxonomy_version      VARCHAR(40)  NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    rejection_reason      VARCHAR(100),
    raw_metadata          TEXT         NOT NULL DEFAULT '{}',
    created_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ml.sample_reviews (
    id                UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    code_sample_id    UUID         NOT NULL,
    reviewer_user_id  UUID,
    review_status     VARCHAR(20)  NOT NULL DEFAULT 'unreviewed',
    reviewed_label_ids TEXT        NOT NULL DEFAULT '[]',
    clean_confirmed   BOOLEAN      NOT NULL DEFAULT FALSE,
    notes             TEXT,
    started_at        TIMESTAMP,
    completed_at      TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ml.ingestion_outbox (
    id              UUID         DEFAULT RANDOM_UUID() PRIMARY KEY,
    event_type      VARCHAR(50)  NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    payload         TEXT         NOT NULL DEFAULT '{}',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    attempt_count   INT          NOT NULL DEFAULT 0,
    available_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at    TIMESTAMP,
    last_error      TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_prediction_events_pr ON ml.prediction_events(pull_request_id);
CREATE INDEX IF NOT EXISTS idx_prediction_events_status ON ml.prediction_events(status);
CREATE INDEX IF NOT EXISTS idx_sample_reviews_status ON ml.sample_reviews(review_status);
CREATE INDEX IF NOT EXISTS idx_sample_reviews_code_sample ON ml.sample_reviews(code_sample_id);
CREATE INDEX IF NOT EXISTS idx_outbox_status_available ON ml.ingestion_outbox(status, available_at);
