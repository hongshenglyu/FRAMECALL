-- Review only for existing deployments; test runner uses a new synthetic memory database.
ALTER TABLE analysis_task ADD COLUMN current_attempt_id VARCHAR(36);
ALTER TABLE dispute_event ADD COLUMN attempt_id VARCHAR(36);
CREATE TABLE task_attempt (
 id VARCHAR(36) PRIMARY KEY,
 task_id BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL,
 active BOOLEAN NOT NULL,
 owner_instance VARCHAR(36) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE,
 finished_at TIMESTAMP WITH TIME ZONE,
 error_code VARCHAR(80)
);
CREATE INDEX idx_attempt_task_active ON task_attempt(task_id,active);
