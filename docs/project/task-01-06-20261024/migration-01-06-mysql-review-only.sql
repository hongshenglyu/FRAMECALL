-- REVIEW ONLY, never executed against a business database.
-- Stop Java and AI workers; review backups/schema and maintenance window before any deployment.
ALTER TABLE task_attempt ADD COLUMN execution_finished BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE task_attempt ADD COLUMN cancel_outcome VARCHAR(24) NULL;
-- Keep legacy active rows quarantined. No backfill asserting old work has stopped.
