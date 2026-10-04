-- Inbox of processed delta events. Idempotency is per event, not per candidate:
-- the same candidate legitimately changes many times.
CREATE TABLE candidate_delta_event (
    event_id     UUID         PRIMARY KEY,
    tenant_id    VARCHAR(100) NOT NULL,
    candidate_id VARCHAR(100) NOT NULL,
    status       VARCHAR(30)  NOT NULL,
    -- number of claims (processing attempts) of this event
    attempts     INTEGER      NOT NULL DEFAULT 1,
    -- processing attempt that currently holds the event; set on every claim, cleared when finished.
    -- COMPLETED / FAILED are only written by this owner, so a stale worker whose lease was
    -- reclaimed can no longer change the outcome (fencing token)
    lease_owner  UUID,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    last_error   VARCHAR(1000),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX candidate_delta_event_candidate
    ON candidate_delta_event (tenant_id, candidate_id);
