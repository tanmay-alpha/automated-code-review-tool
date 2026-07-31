-- ============================================
-- V10: prediction events, sample reviews, ingestion outbox
-- ============================================
-- Introduces three tables:
--
-- 1. ml.prediction_events
--    Records ALL detector output, including results that cannot
--    become normal findings (unmapped files, invalid lines, etc.).
--    This preserves ML debugging evidence without misattributing.
--
-- 2. ml.sample_reviews
--    Tracks per-sample review completion state. A sample without
--    a positive annotation is NOT automatically negative.
--
-- 3. ml.ingestion_outbox
--    Decouples dataset capture from normal PR review transactions.
--    Review inserts an outbox event; a background worker consumes it.
-- ============================================

-- ============================================
-- 1. ml.prediction_events
-- ============================================
CREATE TABLE IF NOT EXISTS ml.prediction_events (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    code_sample_id        UUID         NULL REFERENCES ml.code_samples(id) ON DELETE SET NULL,
    pull_request_id       UUID         NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    file_path             TEXT         NULL,
    reported_line_start   INT          NULL,
    reported_line_end     INT          NULL,
    anti_pattern_id       VARCHAR(80)  NOT NULL,
    category              VARCHAR(30)  NOT NULL,
    severity              VARCHAR(10)  NOT NULL,
    confidence            DECIMAL(5,4) NOT NULL,
    engine                VARCHAR(60)  NOT NULL,
    model_version         VARCHAR(100) NOT NULL,
    taxonomy_version      VARCHAR(40)  NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    rejection_reason      VARCHAR(100) NULL,
    raw_metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_prediction_status
        CHECK (status IN (
            'persisted',
            'rejected_unmapped_file',
            'rejected_invalid_line',
            'rejected_unknown_taxonomy',
            'rejected_invalid_confidence',
            'rejected_duplicate'
        )),
    CONSTRAINT chk_prediction_severity
        CHECK (severity IN ('critical', 'major', 'minor')),
    CONSTRAINT chk_prediction_confidence
        CHECK (confidence >= 0 AND confidence <= 1),

    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_prediction_events_pr
    ON ml.prediction_events(pull_request_id);

CREATE INDEX IF NOT EXISTS idx_prediction_events_status
    ON ml.prediction_events(status);

CREATE INDEX IF NOT EXISTS idx_prediction_events_code_sample
    ON ml.prediction_events(code_sample_id);

CREATE INDEX IF NOT EXISTS idx_prediction_events_created_at
    ON ml.prediction_events(created_at DESC);

COMMENT ON TABLE ml.prediction_events IS
    'All detector output including rejected predictions. Never stores raw source.';


-- ============================================
-- 2. ml.sample_reviews
-- ============================================
CREATE TABLE IF NOT EXISTS ml.sample_reviews (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    code_sample_id    UUID         NOT NULL REFERENCES ml.code_samples(id) ON DELETE CASCADE,
    reviewer_user_id  UUID         NULL,
    review_status     VARCHAR(20)  NOT NULL DEFAULT 'unreviewed',
    reviewed_label_ids JSONB      NOT NULL DEFAULT '[]'::jsonb,
    clean_confirmed   BOOLEAN      NOT NULL DEFAULT FALSE,
    notes             TEXT         NULL,
    started_at        TIMESTAMPTZ  NULL,
    completed_at      TIMESTAMPTZ  NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_sample_review_status
        CHECK (review_status IN (
            'unreviewed', 'in_progress', 'complete', 'needs_adjudication'
        )),

    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sample_reviews_sample_reviewer
    ON ml.sample_reviews(code_sample_id, reviewer_user_id)
    WHERE reviewer_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sample_reviews_status
    ON ml.sample_reviews(review_status);

CREATE INDEX IF NOT EXISTS idx_sample_reviews_code_sample
    ON ml.sample_reviews(code_sample_id);

COMMENT ON TABLE ml.sample_reviews IS
    'Per-sample review state. A sample is negative only when explicitly confirmed clean.';


-- ============================================
-- 3. ml.ingestion_outbox
-- ============================================
CREATE TABLE IF NOT EXISTS ml.ingestion_outbox (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50)  NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    attempt_count   INT          NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ  NULL,
    last_error      TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_outbox_status
        CHECK (status IN ('pending', 'processing', 'completed', 'failed', 'dead_letter')),

    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_available
    ON ml.ingestion_outbox(status, available_at);

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON ml.ingestion_outbox(aggregate_type, aggregate_id);

COMMENT ON TABLE ml.ingestion_outbox IS
    'Decouples dataset capture from PR review transactions.';
