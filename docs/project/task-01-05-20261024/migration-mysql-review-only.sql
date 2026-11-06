-- NOT EXECUTED. Requires offline backup, maintenance window and schema review.
-- Application must be a SINGLE instance; stop old Java and AI workers before rollout.
ALTER TABLE analysis_task ADD COLUMN current_attempt_id VARCHAR(36) NULL;
ALTER TABLE dispute_event ADD COLUMN attempt_id VARCHAR(36) NULL;
CREATE TABLE task_attempt (
 id VARCHAR(36) NOT NULL PRIMARY KEY,
 task_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 active BOOLEAN NOT NULL,
 owner_instance VARCHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 started_at DATETIME(6) NULL,
 finished_at DATETIME(6) NULL,
 error_code VARCHAR(80) NULL,
 INDEX idx_attempt_task_active(task_id,active)
) ENGINE=InnoDB;
-- Keep legacy current_attempt_id/attempt_id NULL; never fabricate run history.
-- PENDING/RUNNING legacy rows require operator reconciliation, not automatic replay.
