CREATE TABLE qa_claim (
  id BIGINT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  ordinal INT NOT NULL,
  text TEXT NOT NULL,
  verification_status VARCHAR(32) NOT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_qa_claim_ordinal (message_id, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE qa_citation
  ADD COLUMN claim_id BIGINT NULL,
  ADD COLUMN source_id VARCHAR(16) NULL,
  ADD COLUMN quote TEXT NULL,
  ADD COLUMN char_start INT NULL,
  ADD COLUMN char_end INT NULL,
  ADD COLUMN content_hash CHAR(64) NULL,
  ADD COLUMN document_generation BIGINT NULL,
  ADD COLUMN verification_status VARCHAR(32) NULL,
  ADD KEY idx_qa_citation_claim (claim_id);

ALTER TABLE qa_message
  ADD COLUMN answer_status VARCHAR(32) NULL,
  ADD COLUMN verification_mode VARCHAR(32) NULL,
  ADD COLUMN verifier_model VARCHAR(120) NULL,
  ADD COLUMN verification_latency_ms BIGINT NULL,
  ADD COLUMN verification_prompt_tokens INT NULL,
  ADD COLUMN verification_completion_tokens INT NULL,
  ADD COLUMN rejected_claim_count INT NULL;
