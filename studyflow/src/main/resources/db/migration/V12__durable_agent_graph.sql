-- v1 runs remain readable; only v2 runs participate in durable graph scheduling.
ALTER TABLE agent_run
  ADD COLUMN runtime_version INT NOT NULL DEFAULT 1,
  ADD COLUMN stage VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
  ADD COLUMN last_node VARCHAR(32) NOT NULL DEFAULT '__START__',
  ADD COLUMN state_json MEDIUMTEXT,
  ADD COLUMN request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
  ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
  ADD COLUMN claim_token VARCHAR(36),
  ADD COLUMN lease_until DATETIME(6),
  ADD COLUMN deadline_at DATETIME(6),
  ADD COLUMN next_attempt_at DATETIME(6),
  ADD COLUMN attempts INT NOT NULL DEFAULT 0,
  ADD COLUMN event_seq INT NOT NULL DEFAULT 0,
  ADD COLUMN model_calls INT NOT NULL DEFAULT 0,
  ADD COLUMN usage_incomplete BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN retry_of BIGINT,
  ADD UNIQUE KEY uk_agent_request (user_id, request_key),
  ADD KEY idx_agent_dispatch (runtime_version, status, next_attempt_at),
  ADD KEY idx_agent_lease (runtime_version, status, lease_until);

CREATE TABLE agent_event (
  run_id BIGINT NOT NULL,
  seq INT NOT NULL,
  type VARCHAR(40) NOT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  create_time DATETIME(6) NOT NULL,
  PRIMARY KEY (run_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_artifact (
  run_id BIGINT PRIMARY KEY,
  title VARCHAR(120) NOT NULL,
  markdown MEDIUMTEXT NOT NULL,
  verification_status VARCHAR(40) NOT NULL,
  evidence_json MEDIUMTEXT NOT NULL,
  create_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Old in-process runs cannot be resumed: no persisted node checkpoint existed.
UPDATE agent_run SET status='FAILED', error_message='旧版执行未保存检查点，请创建新的 Agent 任务', update_time=NOW()
WHERE runtime_version=1 AND status='RUNNING';
