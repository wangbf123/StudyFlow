-- Core schema for empty databases; existing Docker-initialized databases are preserved.
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(120) NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL DEFAULT 'USER',
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_base (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(80) NOT NULL,
  description VARCHAR(500) DEFAULT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  KEY idx_kb_user (user_id),
  KEY idx_kb_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS document (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  original_file_name VARCHAR(255) NOT NULL,
  file_type VARCHAR(32) NOT NULL,
  file_size BIGINT NOT NULL,
  file_md5 CHAR(32) NOT NULL,
  bucket VARCHAR(80) NOT NULL,
  object_key VARCHAR(500) NOT NULL,
  parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  parser_type VARCHAR(32) DEFAULT NULL,
  page_count INT DEFAULT NULL,
  ocr_page_count INT NOT NULL DEFAULT 0,
  ocr_elapsed_ms BIGINT DEFAULT NULL,
  summary TEXT DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  KEY idx_doc_user_kb (user_id, knowledge_base_id),
  KEY idx_doc_md5 (file_md5),
  KEY idx_doc_parse_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS parse_task (
  id BIGINT PRIMARY KEY,
  task_no VARCHAR(64) NOT NULL,
  document_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  max_retry_count INT NOT NULL DEFAULT 3,
  error_message VARCHAR(1000) DEFAULT NULL,
  started_time DATETIME DEFAULT NULL,
  finished_time DATETIME DEFAULT NULL,
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_task_no (task_no),
  KEY idx_task_doc (document_id),
  KEY idx_task_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS document_chunk (
  id BIGINT PRIMARY KEY,
  document_id BIGINT NOT NULL,
  knowledge_base_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  chunk_index INT NOT NULL,
  content TEXT NOT NULL,
  char_count INT NOT NULL,
  page_number INT DEFAULT NULL,
  parser_type VARCHAR(32) DEFAULT NULL,
  ocr_confidence DOUBLE DEFAULT NULL,
  embedding MEDIUMTEXT DEFAULT NULL,
  embedding_model VARCHAR(120) DEFAULT NULL,
  embedding_dim INT DEFAULT NULL,
  create_time DATETIME NOT NULL,
  KEY idx_chunk_doc (document_id),
  KEY idx_chunk_kb (user_id, knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS ai_summary (
  id BIGINT PRIMARY KEY,
  document_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  summary TEXT NOT NULL,
  keywords VARCHAR(500) DEFAULT NULL,
  provider VARCHAR(64) NOT NULL DEFAULT 'mock',
  create_time DATETIME NOT NULL,
  update_time DATETIME NOT NULL,
  UNIQUE KEY uk_summary_doc (document_id),
  KEY idx_summary_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
