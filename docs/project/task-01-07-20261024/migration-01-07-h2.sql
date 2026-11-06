-- Synthetic in-memory H2 migration test only. Existing records remain visible.
ALTER TABLE analysis_task ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
-- Do not infer attempt references or add cascading foreign keys.
