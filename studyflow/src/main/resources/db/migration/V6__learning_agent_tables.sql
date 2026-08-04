CREATE TABLE IF NOT EXISTS agent_session (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  KEY idx_agent_session_user_kb (user_id, knowledge_base_id, update_time),
  KEY idx_agent_session_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_run (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  goal TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  final_answer MEDIUMTEXT DEFAULT NULL,
  model_name VARCHAR(120) DEFAULT NULL,
  total_steps INT NOT NULL DEFAULT 0,
  prompt_tokens INT DEFAULT NULL,
  completion_tokens INT DEFAULT NULL,
  latency_ms BIGINT DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  KEY idx_agent_run_session_time (session_id, create_time),
  KEY idx_agent_run_user_kb (user_id, knowledge_base_id, create_time),
  KEY idx_agent_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_message (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  run_id BIGINT DEFAULT NULL,
  role VARCHAR(32) NOT NULL,
  content MEDIUMTEXT NOT NULL,
  create_time DATETIME NOT NULL,
  KEY idx_agent_message_session_time (session_id, create_time),
  KEY idx_agent_message_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_step (
  id BIGINT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  step_no INT NOT NULL,
  step_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  tool_name VARCHAR(80) DEFAULT NULL,
  input_json TEXT DEFAULT NULL,
  output_json MEDIUMTEXT DEFAULT NULL,
  reasoning_summary VARCHAR(500) DEFAULT NULL,
  latency_ms BIGINT DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_agent_step_run_no (run_id, step_no),
  KEY idx_agent_step_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_source (
  id BIGINT PRIMARY KEY,
  run_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  chunk_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  document_name VARCHAR(255) NOT NULL,
  content_preview VARCHAR(1500) NOT NULL,
  score DOUBLE DEFAULT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_agent_source_run_chunk (run_id, chunk_id),
  KEY idx_agent_source_run (run_id),
  KEY idx_agent_source_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
