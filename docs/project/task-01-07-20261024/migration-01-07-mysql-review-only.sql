-- REVIEW ONLY. Not executed on any real database. Review backup/schema before deployment.
ALTER TABLE analysis_task ADD COLUMN archived_at DATETIME(6) NULL;
-- No destructive backfill, FK cascade, media deletion or active-attempt release.
