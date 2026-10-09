ALTER TABLE document ADD COLUMN generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE document_chunk ADD COLUMN generation BIGINT NOT NULL DEFAULT 1;
ALTER TABLE document_chunk ADD UNIQUE KEY uk_chunk_generation (document_id, generation, chunk_index);
ALTER TABLE parse_task
  ADD COLUMN stage VARCHAR(32) NOT NULL DEFAULT 'PARSE',
  ADD COLUMN generation BIGINT NOT NULL DEFAULT 1,
  ADD COLUMN embedding_model VARCHAR(120) DEFAULT NULL,
  ADD COLUMN embedding_dimension INT DEFAULT NULL,
  ADD COLUMN claim_token VARCHAR(64) DEFAULT NULL,
  ADD COLUMN lease_until DATETIME(6) DEFAULT NULL,
  ADD COLUMN next_attempt_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  ADD KEY idx_pipeline_due (status, next_attempt_time),
  ADD KEY idx_pipeline_lease (status, lease_until);
CREATE TABLE pipeline_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  stage VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  claim_token VARCHAR(64) DEFAULT NULL,
  lease_until DATETIME(6) DEFAULT NULL,
  next_attempt_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  attempt_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000) DEFAULT NULL,
  create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_pipeline_event (task_id, stage),
  KEY idx_pipeline_outbox_due (status, next_attempt_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Existing nonterminal tasks will be recovered by the scheduler after deployment.
UPDATE parse_task SET status = 'RETRYING', claim_token = NULL, lease_until = NULL
  WHERE status = 'PROCESSING';
