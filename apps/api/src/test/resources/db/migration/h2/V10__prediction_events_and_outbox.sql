-- ============================================
-- V10 (H2 mirror): prediction events, sample reviews, outbox
-- ============================================

CREATE TABLE IF NOT EXISTS prediction_events (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
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
    raw_metadata          JSONB        NOT NULL DEFAULT '{}',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sample_reviews (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    code_sample_id    UUID         NOT NULL,
    reviewer_user_id  UUID,
    review_status     VARCHAR(20)  NOT NULL DEFAULT 'unreviewed',
    reviewed_label_ids JSONB      NOT NULL DEFAULT '[]',
    clean_confirmed   BOOLEAN      NOT NULL DEFAULT FALSE,
    notes             TEXT,
    started_at        TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ingestion_outbox (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50)  NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    attempt_count   INT          NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prediction_events_pr ON prediction_events(pull_request_id);
CREATE INDEX IF NOT EXISTS idx_prediction_events_status ON prediction_events(status);
CREATE INDEX IF NOT EXISTS idx_sample_reviews_status ON sample_reviews(review_status);
CREATE INDEX IF NOT EXISTS idx_sample_reviews_code_sample ON sample_reviews(code_sample_id);
CREATE INDEX IF NOT EXISTS idx_outbox_status_available ON ingestion_outbox(status, available_at);
