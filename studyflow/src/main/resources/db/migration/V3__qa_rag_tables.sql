CREATE TABLE IF NOT EXISTS qa_session (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  title VARCHAR(120) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  KEY idx_qa_session_user_kb (user_id, knowledge_base_id, create_time),
  KEY idx_qa_session_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_message (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  role VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
  content MEDIUMTEXT NOT NULL,
  model_name VARCHAR(120) DEFAULT NULL,
  prompt_tokens INT DEFAULT NULL,
  completion_tokens INT DEFAULT NULL,
  latency_ms BIGINT DEFAULT NULL,
  error_message VARCHAR(1000) DEFAULT NULL,
  create_time DATETIME NOT NULL,
  KEY idx_qa_message_session_time (session_id, create_time),
  KEY idx_qa_message_user_kb (user_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_citation (
  id BIGINT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  document_id BIGINT NOT NULL,
  chunk_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  document_name VARCHAR(255) NOT NULL,
  content_preview VARCHAR(1000) NOT NULL,
  score DOUBLE DEFAULT NULL,
  create_time DATETIME NOT NULL,
  KEY idx_qa_citation_message (message_id),
  KEY idx_qa_citation_chunk (chunk_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
