-- Fencing token of the worker that currently holds an IN_PROGRESS record. Set on every claim,
-- cleared when the record is finished. COMPLETED / FAILED are only written by this owner, so a
-- stale worker whose claim was taken over can no longer change the record.
-- Rows that are IN_PROGRESS when this runs have no owner: nobody can finish them, and they are
-- reclaimed (with an owner) once their claim timeout has passed.
ALTER TABLE candidate_migration
    ADD COLUMN lease_owner UUID;
