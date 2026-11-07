-- H2 only. On Postgres, ml.ingestion_outbox.payload and
-- ml.prediction_events.raw_metadata are already JSONB (V10); the entities now
-- bind them as JSON, so the H2 mirror needs a matching JSON type to pass
-- schema validation. There is intentionally no db/migration counterpart.
ALTER TABLE ml.ingestion_outbox ALTER COLUMN payload DROP DEFAULT;
ALTER TABLE ml.ingestion_outbox ALTER COLUMN payload SET DATA TYPE JSON;
ALTER TABLE ml.ingestion_outbox ALTER COLUMN payload SET DEFAULT JSON '{}';

ALTER TABLE ml.prediction_events ALTER COLUMN raw_metadata DROP DEFAULT;
ALTER TABLE ml.prediction_events ALTER COLUMN raw_metadata SET DATA TYPE JSON;
ALTER TABLE ml.prediction_events ALTER COLUMN raw_metadata SET DEFAULT JSON '{}';
