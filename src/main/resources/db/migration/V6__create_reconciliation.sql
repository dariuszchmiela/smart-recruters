-- One verification pass of a tenant: compares SAP and SmartRecruiters directly,
-- independent of candidate_migration (what the migration engine believes happened).
CREATE TABLE reconciliation_run (
    run_id                     UUID         PRIMARY KEY,
    tenant_id                  VARCHAR(100) NOT NULL,
    status                     VARCHAR(30)  NOT NULL,
    page_size                  INTEGER      NOT NULL CHECK (page_size > 0),
    source_count               BIGINT       NOT NULL DEFAULT 0,
    target_count               BIGINT       NOT NULL DEFAULT 0,
    matched_count              BIGINT       NOT NULL DEFAULT 0,
    missing_in_target_count    BIGINT       NOT NULL DEFAULT 0,
    unexpected_in_target_count BIGINT       NOT NULL DEFAULT 0,
    mismatched_count           BIGINT       NOT NULL DEFAULT 0,
    -- worker run that currently executes the run; progress and completion are conditional on it
    lease_owner                UUID,
    last_error                 VARCHAR(1000),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at               TIMESTAMPTZ
);

-- at most one unfinished run per tenant; COMPLETED / FAILED runs do not block a new one
CREATE UNIQUE INDEX reconciliation_run_one_unfinished_per_tenant
    ON reconciliation_run (tenant_id)
    WHERE status IN ('PENDING', 'RUNNING');

-- recovery scan: PENDING runs and RUNNING runs without progress
CREATE INDEX reconciliation_run_status_updated_at
    ON reconciliation_run (status, updated_at);

-- Working table where the source scan and the target scan meet. Holds identity and fingerprints
-- only - no candidate data is duplicated here.
CREATE TABLE candidate_reconciliation_item (
    run_id             UUID         NOT NULL REFERENCES reconciliation_run (run_id) ON DELETE CASCADE,
    tenant_id          VARCHAR(100) NOT NULL,
    external_id        VARCHAR(100) NOT NULL,
    source_seen        BOOLEAN      NOT NULL DEFAULT FALSE,
    target_seen        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- lowercase hex SHA-256 of the canonical candidate representation (see CandidateFingerprint)
    source_fingerprint CHAR(64),
    target_fingerprint CHAR(64),
    -- set when the run is finalized: MATCHED, MISSING_IN_TARGET, UNEXPECTED_IN_TARGET, MISMATCHED
    result             VARCHAR(30),
    PRIMARY KEY (run_id, external_id)
);

-- difference listing: WHERE run_id = ? AND result = ? ORDER BY external_id
CREATE INDEX candidate_reconciliation_item_result
    ON candidate_reconciliation_item (run_id, result, external_id);
