ALTER TABLE candidate_migration
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
