-- Only tested against newly created synthetic in-memory H2 databases.
ALTER TABLE task_attempt ADD COLUMN execution_finished BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE task_attempt ADD COLUMN cancel_outcome VARCHAR(24);
-- Legacy active rows remain quarantined; FALSE is conservative, never an automatic recovery.
